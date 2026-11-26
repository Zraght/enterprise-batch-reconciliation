package com.joseantonio.reconciliation.application.service;

import com.joseantonio.reconciliation.application.port.in.UploadBatchCommand;
import com.joseantonio.reconciliation.application.port.in.UploadBatchUseCase;
import com.joseantonio.reconciliation.application.port.in.UploadResult;
import com.joseantonio.reconciliation.application.port.out.BatchDispatchPort;
import com.joseantonio.reconciliation.application.port.out.BatchRepositoryPort;
import com.joseantonio.reconciliation.application.port.out.FileStoragePort;
import com.joseantonio.reconciliation.application.port.out.NewBatch;
import com.joseantonio.reconciliation.application.port.out.RegistrationOutcome;
import com.joseantonio.reconciliation.application.port.out.StoredFile;
import com.joseantonio.reconciliation.domain.exception.BatchConflictException;
import com.joseantonio.reconciliation.domain.exception.InvalidRequestException;
import com.joseantonio.reconciliation.domain.model.BatchSnapshot;
import com.joseantonio.reconciliation.domain.model.BatchStatus;
import com.joseantonio.reconciliation.domain.model.FileFormat;
import com.joseantonio.reconciliation.domain.model.SourceName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Registers an uploaded file as a batch and queues it. Idempotent on (source, SHA-256 of the content):
 * uploading the same file again never creates a second batch; if the earlier one FAILED it is re-queued.
 *
 * <p>The optional declared settlement period belongs to the registration: replaying the same file with a DIFFERENT
 * period is refused (409) instead of silently ignoring it, except for a FAILED batch, which is being reprocessed anyway
 * and takes the new period.</p>
 */
public final class BatchRegistrationService implements UploadBatchUseCase {

    private static final Logger log = LoggerFactory.getLogger(BatchRegistrationService.class);
    static final int MAX_FILE_NAME = 255;

    private final FileStoragePort storage;
    private final BatchRepositoryPort batches;
    private final BatchDispatchPort dispatcher;
    private final Clock clock;

    public BatchRegistrationService(FileStoragePort storage, BatchRepositoryPort batches,
                                    BatchDispatchPort dispatcher, Clock clock) {
        this.storage = storage;
        this.batches = batches;
        this.dispatcher = dispatcher;
        this.clock = clock;
    }

    @Override
    public UploadResult upload(UploadBatchCommand command) {
        String sourceName = SourceName.require(command.sourceName());
        if (command.content() == null) {
            throw new InvalidRequestException("EMPTY_FILE", "No file content was supplied");
        }
        String fileName = sanitizeFileName(command.originalFileName());
        FileFormat format = FileFormat.fromFileName(fileName);
        validatePeriod(command.periodStart(), command.periodEnd());

        StoredFile stored = storage.store(command.content(), fileName);
        boolean keepFile = false;
        try {
            if (stored.sizeBytes() == 0) {
                throw new InvalidRequestException("EMPTY_FILE", "The uploaded file is empty");
            }
            RegistrationOutcome outcome = batches.register(new NewBatch(sourceName, fileName, format,
                    stored.sha256(), stored.sizeBytes(), stored.location(), clock.instant(),
                    command.periodStart(), command.periodEnd()));
            BatchSnapshot batch = outcome.batch();

            if (outcome.created()) {
                keepFile = true;
                dispatchQuietly(batch.batchId());
                return new UploadResult(batch, UploadResult.Outcome.CREATED);
            }
            if (batch.status() == BatchStatus.FAILED) {
                Instant start = command.periodStart() != null ? command.periodStart() : batch.declaredPeriodStart();
                Instant end = command.periodEnd() != null ? command.periodEnd() : batch.declaredPeriodEnd();
                if (batches.resetForRetry(batch.batchId(), start, end)) {
                    log.info("Re-queuing FAILED batch {} because the same file was uploaded again", batch.batchId());
                    dispatchQuietly(batch.batchId());
                    BatchSnapshot refreshed = batches.findById(batch.batchId()).orElse(batch);
                    return new UploadResult(refreshed, UploadResult.Outcome.RETRIED);
                }
            }
            requireSamePeriod(command, batch);
            return new UploadResult(batches.findById(batch.batchId()).orElse(batch), UploadResult.Outcome.EXISTING);
        } finally {
            if (!keepFile) {
                storage.delete(stored.location());
            }
        }
    }

    /** A declared period that contradicts the one the batch was registered with must not be silently ignored. */
    private static void requireSamePeriod(UploadBatchCommand command, BatchSnapshot existing) {
        if (command.periodStart() == null) {
            return;
        }
        if (!Objects.equals(command.periodStart(), existing.declaredPeriodStart())
                || !Objects.equals(command.periodEnd(), existing.declaredPeriodEnd())) {
            throw new BatchConflictException("BATCH_PERIOD_CONFLICT",
                    "This file is already registered as batch " + existing.batchId() + " (" + existing.status()
                            + ") with " + (existing.declaredPeriodStart() == null
                            ? "no declared settlement period" : "another settlement period")
                            + "; the period cannot be changed once the batch is registered");
        }
    }

    static void validatePeriod(Instant start, Instant end) {
        if ((start == null) != (end == null)) {
            throw new InvalidRequestException("INVALID_PERIOD", "periodStart and periodEnd must be supplied together");
        }
        if (start != null && end.isBefore(start)) {
            throw new InvalidRequestException("INVALID_PERIOD", "periodEnd must not be before periodStart");
        }
    }

    /**
     * The client-supplied name is only metadata, but it is stored and shown back: keep just the last path segment,
     * drop control characters and cap the length so it always fits its column.
     */
    static String sanitizeFileName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidRequestException("INVALID_FILE_NAME", "The uploaded file has no name");
        }
        String name = raw.substring(Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\')) + 1);
        StringBuilder clean = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 0x20 && c != 0x7f) {
                clean.append(c);
            }
        }
        String result = clean.toString().trim();
        if (result.isEmpty()) {
            throw new InvalidRequestException("INVALID_FILE_NAME", "The uploaded file has no usable name");
        }
        if (result.length() > MAX_FILE_NAME) {
            int dot = result.lastIndexOf('.');
            String extension = dot >= 0 && result.length() - dot <= 10 ? result.substring(dot) : "";
            result = result.substring(0, MAX_FILE_NAME - extension.length()) + extension;
        }
        return result;
    }

    /** The batch is already durable as PENDING; if dispatching fails the recovery scheduler will pick it up. */
    private void dispatchQuietly(long batchId) {
        try {
            dispatcher.dispatch(batchId);
        } catch (RuntimeException e) {
            log.error("Could not dispatch batch {} now; it stays PENDING for the recovery scheduler", batchId, e);
        }
    }
}
