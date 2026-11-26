package com.joseantonio.reconciliation.domain.model;

import java.math.BigDecimal;
import java.util.Currency;

/**
 * Result of reconciling one reference.
 *
 * @param discrepancyAmount signed difference {@code settlement - ledger} (a missing side counts as zero);
 *                          {@code null} when it cannot be computed because currencies differ
 * @param currency          currency of {@code discrepancyAmount}; {@code null} when not computable
 * @param ledgerTxId        traceability link to the ledger entry, if any
 * @param settlementId      traceability link to the settlement, if any
 * @param detail            human readable explanation
 * @param ruleName          strategy that produced the outcome
 */
public record ReconciliationOutcome(
        ReferenceCode reference,
        MatchStatus status,
        BigDecimal discrepancyAmount,
        Currency currency,
        Long ledgerTxId,
        Long settlementId,
        String detail,
        String ruleName) {

    public boolean isDiscrepancy() {
        return status != MatchStatus.MATCHED;
    }

    /** Same outcome with an extended explanation (used to add cross-batch / ledger-state context). */
    public ReconciliationOutcome withDetail(String newDetail) {
        return new ReconciliationOutcome(reference, status, discrepancyAmount, currency, ledgerTxId, settlementId,
                newDetail, ruleName);
    }
}
