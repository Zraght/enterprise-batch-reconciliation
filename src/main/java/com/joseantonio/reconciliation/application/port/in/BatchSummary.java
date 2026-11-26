package com.joseantonio.reconciliation.application.port.in;

import com.joseantonio.reconciliation.domain.model.BatchStatus;
import com.joseantonio.reconciliation.domain.model.MatchStatus;
import com.joseantonio.reconciliation.domain.model.Severity;
import com.joseantonio.reconciliation.domain.model.SettlementWindow;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Executive summary of a COMPLETED batch.
 *
 * @param totalRecords              records read from the file (valid + invalid)
 * @param invalidRecordsByReason    why records were rejected (reason code -> count)
 * @param evaluatedReferences       references that went through reconciliation (matched + discrepancies)
 * @param matchedTransactions       references reconciled with an exact match
 * @param discrepancyCount          references with any discrepancy, as found when the batch ran
 * @param resolvedByLaterBatches    of those, how many a later batch has since closed
 * @param openDiscrepancies         {@code discrepancyCount - resolvedByLaterBatches}
 * @param discrepanciesByStatus     discrepancy count per match status
 * @param totalDiscrepancyAmount    sum of |discrepancy| per currency (amounts in different currencies are never added)
 * @param reconciliationRatePercent matched / evaluated * 100, 2 decimals, banker's rounding
 * @param periodSource              whether the settlement period was DECLARED at upload or INFERRED from the file
 * @param dueCutoff                 movements booked up to this instant were due for the batch (period end - lag)
 * @param deferredLedgerEntries     movements still within the settlement lag when the batch finished: pending
 *                                  settlement, NOT reported as discrepancies, still eligible for later batches
 * @param alertsGenerated           total audit alerts
 */
public record BatchSummary(
        long batchId,
        BatchStatus status,
        String sourceName,
        int totalRecords,
        int validRecords,
        int invalidRecords,
        Map<String, Long> invalidRecordsByReason,
        Instant periodStart,
        Instant periodEnd,
        SettlementWindow.Source periodSource,
        Instant dueCutoff,
        long evaluatedReferences,
        long matchedTransactions,
        long discrepancyCount,
        long resolvedByLaterBatches,
        long openDiscrepancies,
        Map<MatchStatus, Long> discrepanciesByStatus,
        Map<String, BigDecimal> totalDiscrepancyAmount,
        BigDecimal reconciliationRatePercent,
        int deferredLedgerEntries,
        long alertsGenerated,
        Map<Severity, Long> alertsBySeverity,
        Instant startedAt,
        Instant completedAt) {
}
