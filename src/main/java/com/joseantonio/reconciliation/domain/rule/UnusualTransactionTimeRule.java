package com.joseantonio.reconciliation.domain.rule;

import com.joseantonio.reconciliation.domain.model.AccountActivity;
import com.joseantonio.reconciliation.domain.model.AuditFinding;
import com.joseantonio.reconciliation.domain.model.LedgerEntry;
import com.joseantonio.reconciliation.domain.model.Severity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Unusual Transaction Time: high-value operation (amount &ge; configured threshold for its currency)
 * executed outside the configured operating hours. MEDIUM, or HIGH when the amount is &ge; 5x the threshold.
 */
public final class UnusualTransactionTimeRule implements AuditRule {

    public static final String NAME = "UNUSUAL_TRANSACTION_TIME";
    private static final BigDecimal FIVE = BigDecimal.valueOf(5);

    private final OperatingHoursPolicy policy;

    public UnusualTransactionTimeRule(OperatingHoursPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<AuditFinding> evaluate(AccountActivity activity) {
        List<AuditFinding> findings = new ArrayList<>();
        for (LedgerEntry entry : activity.entries()) {
            var threshold = policy.highValueThreshold(entry.amount().currency());
            if (threshold.isEmpty() || entry.amount().amount().compareTo(threshold.get()) < 0) {
                continue;
            }
            if (!policy.isOutsideOperatingHours(entry.timestamp())) {
                continue;
            }
            Severity severity = entry.amount().amount().compareTo(threshold.get().multiply(FIVE)) >= 0
                    ? Severity.HIGH : Severity.MEDIUM;
            findings.add(new AuditFinding(entry.txId(), entry.reference(), NAME, severity,
                    "High-value operation of " + entry.amount().amount().toPlainString() + " "
                            + entry.amount().currencyCode() + " at "
                            + entry.timestamp().atZone(policy.zone()).toLocalTime()
                            + " (" + policy.zone() + ") is outside operating hours "
                            + policy.opensAt() + "-" + policy.closesAt()));
        }
        return findings;
    }
}
