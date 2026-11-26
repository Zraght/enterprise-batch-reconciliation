package com.joseantonio.reconciliation.application.service;

import com.joseantonio.reconciliation.application.port.out.ClaimedBatch;
import com.joseantonio.reconciliation.application.port.out.IngestionChunk;
import com.joseantonio.reconciliation.application.port.out.RawRecord;
import com.joseantonio.reconciliation.application.port.out.RecordSource;
import com.joseantonio.reconciliation.application.port.out.SettlementFileReaderPort;
import com.joseantonio.reconciliation.application.port.out.SettlementStorePort;
import com.joseantonio.reconciliation.domain.exception.FileProcessingException;
import com.joseantonio.reconciliation.domain.model.ExternalSettlement;
import com.joseantonio.reconciliation.domain.model.InvalidRecord;
import com.joseantonio.reconciliation.domain.model.ReferenceCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Phase 1: streams the file, validates every record and persists it in atomic chunks.
 * <ul>
 *   <li>Memory is bounded by the chunk size plus the set of references seen (duplicate detection).</li>
 *   <li>A repeated reference inside the same file is rejected as an invalid record (first occurrence wins). An exact
 *       replay of the first line is reported as {@code DUPLICATE_REFERENCE_IDENTICAL} (benign); a line with the same
 *       reference but a different amount, currency or timestamp is {@code DUPLICATE_REFERENCE_CONFLICT} (a data
 *       quality problem that deserves attention).</li>
 *   <li>The earliest and latest settlement timestamps of the valid records are reported, so the settlement period
 *       can be inferred when the uploader did not declare it.</li>
 *   <li>Structural problems (bad header, malformed file, too many invalid rows, nothing valid) fail the batch.</li>
 * </ul>
 */
public final class SettlementIngestionService {

    private static final Logger log = LoggerFactory.getLogger(SettlementIngestionService.class);
    /** Ingestion owns progress 0..39; reconciliation 40..79; audit 80..99. */
    static final int INGESTION_PROGRESS_CAP = 39;

    /** @param earliest earliest settlement timestamp of the valid records (null when there are none) */
    public record Result(int recordsRead, int validRecords, int invalidRecords, Instant earliest, Instant latest) {
    }

    private final SettlementFileReaderPort reader;
    private final SettlementStorePort store;
    private final SettlementRecordMapper mapper;
    private final ProcessingSettings settings;

    public SettlementIngestionService(SettlementFileReaderPort reader, SettlementStorePort store,
                                      SettlementRecordMapper mapper, ProcessingSettings settings) {
        this.reader = reader;
        this.store = store;
        this.mapper = mapper;
        this.settings = settings;
    }

    public Result ingest(ClaimedBatch batch) {
        int read = 0;
        int valid = 0;
        int invalid = 0;
        List<ExternalSettlement> validChunk = new ArrayList<>(settings.chunkSize());
        List<InvalidRecord> invalidChunk = new ArrayList<>();
        Map<ReferenceCode, Integer> seen = new HashMap<>();
        Instant earliest = null;
        Instant latest = null;
        int inChunk = 0;

        try (RecordSource source = reader.open(batch.storedFileLocation(), batch.fileFormat())) {
            Set<String> columns = source.columns();
            if (!columns.isEmpty() && !columns.containsAll(SettlementRecordMapper.REQUIRED_FIELDS)) {
                throw new FileProcessingException("MISSING_REQUIRED_COLUMNS",
                        "File header must contain the columns " + SettlementRecordMapper.REQUIRED_FIELDS + " but has " + columns);
            }
            RawRecord raw;
            while ((raw = source.next()) != null) {
                read++;
                inChunk++;
                switch (mapper.map(raw)) {
                    case SettlementRecordMapper.Valid v -> {
                        ExternalSettlement settlement = v.settlement();
                        int fingerprint = Objects.hash(settlement.amount(), settlement.timestamp());
                        Integer first = seen.putIfAbsent(settlement.externalRef(), fingerprint);
                        if (first == null) {
                            validChunk.add(settlement);
                            valid++;
                            earliest = earliest == null || settlement.timestamp().isBefore(earliest) ? settlement.timestamp() : earliest;
                            latest = latest == null || settlement.timestamp().isAfter(latest) ? settlement.timestamp() : latest;
                        } else {
                            boolean identical = first == fingerprint;
                            invalidChunk.add(SettlementRecordMapper.invalid(raw,
                                    identical ? "DUPLICATE_REFERENCE_IDENTICAL" : "DUPLICATE_REFERENCE_CONFLICT",
                                    "Reference " + settlement.externalRef() + " already appears earlier in this file"
                                            + (identical ? " with identical content" : " with DIFFERENT content")));
                            invalid++;
                        }
                    }
                    case SettlementRecordMapper.Invalid i -> {
                        invalidChunk.add(i.record());
                        invalid++;
                    }
                }
                if (inChunk >= settings.chunkSize()) {
                    flush(batch, source, validChunk, invalidChunk, inChunk);
                    inChunk = 0;
                }
            }
            if (inChunk > 0) {
                flush(batch, source, validChunk, invalidChunk, inChunk);
            }
        }

        if (read == 0) {
            throw new FileProcessingException("EMPTY_FILE", "The file contains no records");
        }
        if (valid == 0) {
            throw new FileProcessingException("NO_VALID_RECORDS", "None of the " + read + " records is valid");
        }
        BigDecimal allowedInvalid = settings.maxInvalidRatio().multiply(BigDecimal.valueOf(read));
        if (BigDecimal.valueOf(invalid).compareTo(allowedInvalid) > 0) {
            throw new FileProcessingException("TOO_MANY_INVALID_RECORDS",
                    invalid + " of " + read + " records are invalid (maximum ratio " + settings.maxInvalidRatio() + ")");
        }
        log.info("Ingestion finished: read={}, valid={}, invalid={}", read, valid, invalid);
        return new Result(read, valid, invalid, earliest, latest);
    }

    private void flush(ClaimedBatch batch, RecordSource source, List<ExternalSettlement> valid,
                       List<InvalidRecord> invalid, int recordsRead) {
        int progress = (int) Math.min(INGESTION_PROGRESS_CAP, 40L * source.bytesRead() / Math.max(1L, source.totalBytes()));
        store.saveChunk(batch, new IngestionChunk(valid, invalid, recordsRead, progress));
        valid.clear();
        invalid.clear();
    }
}
