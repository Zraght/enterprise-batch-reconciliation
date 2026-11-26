package com.joseantonio.reconciliation.infrastructure.web.dto;

import com.joseantonio.reconciliation.application.port.in.DiscrepancyView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "One reference that did not reconcile")
public record DiscrepancyResponse(
        long resultId,
        String referenceCode,
        @Schema(description = "MISSING_INTERNAL, MISSING_EXTERNAL or AMOUNT_MISMATCH") String matchStatus,
        @Schema(description = "Signed difference settlement - ledger (missing side counts as 0). Null when the "
                + "currencies differ") BigDecimal discrepancyAmount,
        String currency,
        @Schema(description = "Traceability: internal ledger row, if any") Long ledgerTxId,
        @Schema(description = "Traceability: external settlement row, if any") Long settlementId,
        String detail,
        @Schema(description = "The later batch that closed this discrepancy; null while it is still open")
        Long resolvedByBatchId) {

    public static DiscrepancyResponse from(DiscrepancyView v) {
        return new DiscrepancyResponse(v.resultId(), v.referenceCode(), v.matchStatus().name(),
                v.discrepancyAmount(), v.currency(), v.ledgerTxId(), v.settlementId(), v.detail(), v.resolvedByBatchId());
    }
}
