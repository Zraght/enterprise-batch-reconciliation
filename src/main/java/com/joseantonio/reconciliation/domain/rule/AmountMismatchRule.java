package com.joseantonio.reconciliation.domain.rule;

import com.joseantonio.reconciliation.domain.model.MatchStatus;
import com.joseantonio.reconciliation.domain.model.Money;
import com.joseantonio.reconciliation.domain.model.ReconciliationCandidate;
import com.joseantonio.reconciliation.domain.model.ReconciliationOutcome;

import java.util.Optional;

/**
 * Amount Mismatch: same reference present on both sides, but amount and/or currency differ.
 * <p>When currencies differ no numeric difference is meaningful, so the discrepancy amount is {@code null}
 * and the detail explains the currency conflict.</p>
 */
public final class AmountMismatchRule implements ReconciliationRule {

    public static final String NAME = "AMOUNT_MISMATCH";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Optional<ReconciliationOutcome> evaluate(ReconciliationCandidate c) {
        if (c.ledger() == null || c.settlement() == null) {
            return Optional.empty();
        }
        Money ledger = c.ledger().amount();
        Money external = c.settlement().amount();
        if (ledger.sameValue(external)) {
            return Optional.empty();
        }
        if (!ledger.sameCurrency(external)) {
            return Optional.of(new ReconciliationOutcome(
                    c.reference(), MatchStatus.AMOUNT_MISMATCH, null, null,
                    c.ledger().txId(), c.settlement().settlementId(),
                    "CURRENCY_MISMATCH: ledger " + ledger.amount().toPlainString() + " " + ledger.currencyCode()
                            + " vs settlement " + external.amount().toPlainString() + " " + external.currencyCode(),
                    NAME));
        }
        var difference = external.amount().subtract(ledger.amount());
        return Optional.of(new ReconciliationOutcome(
                c.reference(), MatchStatus.AMOUNT_MISMATCH, difference, external.currency(),
                c.ledger().txId(), c.settlement().settlementId(),
                "AMOUNT_DIFFERENCE: ledger " + ledger.amount().toPlainString() + " vs settlement "
                        + external.amount().toPlainString() + " " + external.currencyCode(),
                NAME));
    }
}
