package com.joseantonio.reconciliation.domain.model;

import java.time.Instant;

/**
 * Read model of a reconciliation batch and its processing progress.
 *
 * @param declaredPeriodStart  settlement period supplied at upload (both ends, or both null)
 * @param periodStart          effective settlement period (declared, or inferred from the file); null until ingestion ends
 * @param periodSource         DECLARED or INFERRED; null until the period is known
 * @param dueCutoff            ledger movements booked up to this instant were due for this batch; later ones were deferred
 * @param deferredLedgerEntries movements not yet due when the batch finished (pending settlement, not reported)
 */
public record BatchSnapshot(
        long batchId,
        Instant executionDate,
        String sourceName,
        String fileName,
        FileFormat fileFormat,
        BatchStatus status,
        BatchPhase phase,
        int progressPercent,
        int totalRecords,
        int validRecords,
        int invalidRecords,
        String failureReason,
        Instant declaredPeriodStart,
        Instant declaredPeriodEnd,
        Instant periodStart,
        Instant periodEnd,
        SettlementWindow.Source periodSource,
        Instant dueCutoff,
        Integer deferredLedgerEntries,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt) {
}
