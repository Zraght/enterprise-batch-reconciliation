package com.joseantonio.reconciliation.domain.rule;

import com.joseantonio.reconciliation.domain.model.MatchStatus;
import com.joseantonio.reconciliation.domain.model.ReconciliationCandidate;
import com.joseantonio.reconciliation.domain.model.ReconciliationOutcome;

import java.util.Optional;

/**
 * Orphan Detection: a reference present in only one of the two sources.
 * Discrepancy sign convention is {@code settlement - ledger}: a settlement without ledger entry is positive,
 * a ledger entry without settlement is negative.
 */
public final class OrphanDetectionRule implements ReconciliationRule {

    public static final String NAME = "ORPHAN_DETECTION";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Optional<ReconciliationOutcome> evaluate(ReconciliationCandidate c) {
        if (c.ledger() != null && c.settlement() != null) {
            return Optional.empty();
        }
        if (c.ledger() == null) {
            var s = c.settlement();
            return Optional.of(new ReconciliationOutcome(
                    c.reference(), MatchStatus.MISSING_INTERNAL,
                    s.amount().amount(), s.amount().currency(),
                    null, s.settlementId(),
                    "External settlement has no POSTED internal ledger entry with the same reference",
                    NAME));
        }
        var l = c.ledger();
        return Optional.of(new ReconciliationOutcome(
                c.reference(), MatchStatus.MISSING_EXTERNAL,
                l.amount().amount().negate(), l.amount().currency(),
                l.txId(), null,
                "Internal ledger entry has no external settlement with the same reference",
                NAME));
    }
}
