package com.joseantonio.reconciliation.infrastructure.web.dto;

import com.joseantonio.reconciliation.domain.model.BatchSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Status and progress of a batch")
public record BatchStatusResponse(
        long batchId,
        String sourceName,
        String fileName,
        String fileFormat,
        @Schema(description = "PENDING, PROCESSING, COMPLETED or FAILED") String status,
        @Schema(description = "QUEUED, INGESTION, RECONCILIATION, AUDIT or DONE") String phase,
        @Schema(description = "0-100. Ingestion 0-39, reconciliation 40-79, audit 80-99") int progressPercent,
        @Schema(description = "Records read from the file so far (valid + invalid)") int totalRecords,
        int validRecords,
        int invalidRecords,
        @Schema(description = "Only present when status is FAILED") String failureReason,
        @Schema(description = "Settlement period the file covers (declared at upload, or inferred from the file). "
                + "Known once ingestion has finished") Instant periodStart,
        Instant periodEnd,
        @Schema(description = "DECLARED or INFERRED") String periodSource,
        @Schema(description = "Ledger movements booked up to this instant were due for the batch (period end - "
                + "settlement lag); later ones are pending settlement") Instant dueCutoff,
        @Schema(description = "Movements still within the settlement lag when the batch finished: not reported as "
                + "discrepancies, eligible for later batches") Integer deferredLedgerEntries,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt) {

    public static BatchStatusResponse from(BatchSnapshot b) {
        return new BatchStatusResponse(b.batchId(), b.sourceName(), b.fileName(), b.fileFormat().name(),
                b.status().name(), b.phase().name(), b.progressPercent(), b.totalRecords(), b.validRecords(),
                b.invalidRecords(), b.failureReason(), b.periodStart(), b.periodEnd(),
                b.periodSource() == null ? null : b.periodSource().name(), b.dueCutoff(), b.deferredLedgerEntries(),
                b.createdAt(), b.startedAt(), b.completedAt());
    }
}
