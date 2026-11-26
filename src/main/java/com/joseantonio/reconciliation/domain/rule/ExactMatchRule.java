package com.joseantonio.reconciliation.domain.rule;

import com.joseantonio.reconciliation.domain.model.MatchStatus;
import com.joseantonio.reconciliation.domain.model.Money;
import com.joseantonio.reconciliation.domain.model.ReconciliationCandidate;
import com.joseantonio.reconciliation.domain.model.ReconciliationOutcome;

import java.math.BigDecimal;
import java.util.Optional;

/** Exact Match: same reference, same amount and same currency. */
public final class ExactMatchRule implements ReconciliationRule {

    public static final String NAME = "EXACT_MATCH";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Optional<ReconciliationOutcome> evaluate(ReconciliationCandidate c) {
        if (c.ledger() == null || c.settlement() == null) {
            return Optional.empty();
        }
        if (!c.ledger().amount().sameValue(c.settlement().amount())) {
            return Optional.empty();
        }
        Money amount = c.settlement().amount();
        return Optional.of(new ReconciliationOutcome(
                c.reference(), MatchStatus.MATCHED,
                BigDecimal.ZERO.setScale(Money.SCALE), amount.currency(),
                c.ledger().txId(), c.settlement().settlementId(),
                "Reference, amount and currency match",
                NAME));
    }
}
