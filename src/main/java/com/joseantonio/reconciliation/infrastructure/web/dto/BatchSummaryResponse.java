package com.joseantonio.reconciliation.infrastructure.web.dto;

import com.joseantonio.reconciliation.application.port.in.BatchSummary;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

@Schema(description = "Executive summary of a COMPLETED batch")
public record BatchSummaryResponse(
        long batchId,
        String status,
        String sourceName,
        @Schema(description = "Records read from the file (valid + invalid)") int totalRecords,
        int validRecords,
        int invalidRecords,
        @Schema(description = "Why records were rejected (reason code -> count)",
                example = "{\"DUPLICATE_REFERENCE_IDENTICAL\": 1, \"INVALID_AMOUNT\": 1}")
        Map<String, Long> invalidRecordsByReason,
        @Schema(description = "Settlement period of the file") Instant periodStart,
        Instant periodEnd,
        @Schema(description = "DECLARED at upload, or INFERRED from the file") String periodSource,
        @Schema(description = "Movements booked up to this instant were due for the batch") Instant dueCutoff,
        @Schema(description = "References evaluated by the reconciliation (matched + discrepancies)") long evaluatedReferences,
        @Schema(description = "Reconciled transactions (exact matches)") long matchedTransactions,
        @Schema(description = "References with any discrepancy, as found when the batch ran") long discrepancyCount,
        @Schema(description = "Of those, discrepancies that a LATER batch has since closed (late settlement or late "
                + "booking)") long resolvedByLaterBatches,
        @Schema(description = "discrepancyCount - resolvedByLaterBatches") long openDiscrepancies,
        Map<String, Long> discrepanciesByStatus,
        @Schema(description = "Sum of absolute discrepancy amounts per currency; different currencies are never added",
                example = "{\"PEN\": 85.5000}")
        Map<String, BigDecimal> totalDiscrepancyAmount,
        @Schema(description = "matched / evaluated * 100, 2 decimals (banker's rounding)", example = "42.86")
        BigDecimal reconciliationRatePercent,
        @Schema(description = "Ledger movements still within the settlement lag: pending settlement, NOT discrepancies")
        int deferredLedgerEntries,
        long alertsGenerated,
        Map<String, Long> alertsBySeverity,
        Instant startedAt,
        Instant completedAt) {

    public static BatchSummaryResponse from(BatchSummary s) {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        s.discrepanciesByStatus().forEach((k, v) -> byStatus.put(k.name(), v));
        Map<String, Long> bySeverity = new LinkedHashMap<>();
        s.alertsBySeverity().forEach((k, v) -> bySeverity.put(k.name(), v));
        return new BatchSummaryResponse(s.batchId(), s.status().name(), s.sourceName(), s.totalRecords(),
                s.validRecords(), s.invalidRecords(), s.invalidRecordsByReason(), s.periodStart(), s.periodEnd(),
                s.periodSource() == null ? null : s.periodSource().name(), s.dueCutoff(), s.evaluatedReferences(),
                s.matchedTransactions(), s.discrepancyCount(), s.resolvedByLaterBatches(), s.openDiscrepancies(),
                byStatus, new TreeMap<>(s.totalDiscrepancyAmount()), s.reconciliationRatePercent(),
                s.deferredLedgerEntries(), s.alertsGenerated(), bySeverity, s.startedAt(), s.completedAt());
    }
}
