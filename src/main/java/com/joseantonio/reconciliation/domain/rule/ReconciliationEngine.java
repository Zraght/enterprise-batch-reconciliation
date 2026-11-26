package com.joseantonio.reconciliation.domain.rule;

import com.joseantonio.reconciliation.domain.model.ReconciliationCandidate;
import com.joseantonio.reconciliation.domain.model.ReconciliationOutcome;

import java.util.List;
import java.util.Objects;

/**
 * Applies the configured strategies in order; the first one that produces an outcome wins.
 * The ordered list is the extension point: new rules are added by registering another strategy.
 */
public final class ReconciliationEngine {

    private final List<ReconciliationRule> rules;

    public ReconciliationEngine(List<ReconciliationRule> rules) {
        Objects.requireNonNull(rules, "rules");
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("At least one reconciliation rule is required");
        }
        this.rules = List.copyOf(rules);
    }

    /** Default rule chain: orphans first, then exact match, then amount mismatch. */
    public static ReconciliationEngine standard() {
        return new ReconciliationEngine(List.of(
                new OrphanDetectionRule(), new ExactMatchRule(), new AmountMismatchRule()));
    }

    public ReconciliationOutcome reconcile(ReconciliationCandidate candidate) {
        for (ReconciliationRule rule : rules) {
            var outcome = rule.evaluate(candidate);
            if (outcome.isPresent()) {
                return outcome.get();
            }
        }
        throw new IllegalStateException("No reconciliation rule handled reference " + candidate.reference());
    }
}
