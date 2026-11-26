package com.joseantonio.reconciliation.domain.rule;

import com.joseantonio.reconciliation.domain.model.AccountActivity;
import com.joseantonio.reconciliation.domain.model.AuditFinding;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runs every audit strategy and guarantees at most one finding per (audited movement, rule), keeping the highest
 * severity when a rule reports the same movement more than once.
 */
public final class AuditEngine {

    private final List<AuditRule> rules;

    public AuditEngine(List<AuditRule> rules) {
        Objects.requireNonNull(rules, "rules");
        this.rules = List.copyOf(rules);
    }

    /** How far back before an audited movement the rules need history (the maximum over all rules). */
    public Duration historyLookback() {
        return rules.stream().map(AuditRule::lookback).max(Duration::compareTo).orElse(Duration.ZERO);
    }

    public List<AuditFinding> audit(AccountActivity activity) {
        Map<String, AuditFinding> unique = new LinkedHashMap<>();
        for (AuditRule rule : rules) {
            for (AuditFinding finding : rule.evaluate(activity)) {
                String subject = finding.ledgerTxId() != null ? "T" + finding.ledgerTxId() : "R" + finding.reference().value();
                unique.merge(subject + "|" + finding.rule(), finding,
                        (a, b) -> a.severity().ordinal() >= b.severity().ordinal() ? a : b);
            }
        }
        return List.copyOf(unique.values());
    }
}
