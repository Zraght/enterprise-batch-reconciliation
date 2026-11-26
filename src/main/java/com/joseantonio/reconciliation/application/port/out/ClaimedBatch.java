package com.joseantonio.reconciliation.application.port.out;

import com.joseantonio.reconciliation.domain.model.FileFormat;

import java.time.Instant;

/**
 * Lease over a batch granted to ONE worker. {@code attempt} is the fencing token: every write performed on behalf of the
 * batch is conditioned on it, so a stale worker can never overwrite, delete or otherwise touch what a newer attempt owns.
 *
 * @param declaredPeriodStart settlement period declared at upload (both ends, or null to infer it from the file)
 */
public record ClaimedBatch(
        long batchId,
        int attempt,
        String sourceName,
        FileFormat fileFormat,
        String storedFileLocation,
        long fileSizeBytes,
        Instant declaredPeriodStart,
        Instant declaredPeriodEnd) {
}
