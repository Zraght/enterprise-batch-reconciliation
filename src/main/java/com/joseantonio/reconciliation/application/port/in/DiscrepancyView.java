package com.joseantonio.reconciliation.application.port.in;

import com.joseantonio.reconciliation.domain.model.MatchStatus;

import java.math.BigDecimal;

/**
 * @param resolvedByBatchId the later batch that closed this discrepancy (late settlement or late booking), or null
 *                          while it is still open
 */
public record DiscrepancyView(
        long resultId,
        String referenceCode,
        MatchStatus matchStatus,
        BigDecimal discrepancyAmount,
        String currency,
        Long ledgerTxId,
        Long settlementId,
        String detail,
        Long resolvedByBatchId) {
}
