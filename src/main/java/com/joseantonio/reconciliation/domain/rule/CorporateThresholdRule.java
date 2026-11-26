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
 * Corporate Threshold Rule: an operation above the limit configured for its account type and currency,
 * without prior authorisation. Severity grows with the excess: MEDIUM (&gt; limit), HIGH (&ge; 2x), CRITICAL (&ge; 5x).
 */
public final class CorporateThresholdRule implements AuditRule {

    public static final String NAME = "CORPORATE_THRESHOLD";
    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final BigDecimal FIVE = BigDecimal.valueOf(5);

    private final ThresholdPolicy policy;

    public CorporateThresholdRule(ThresholdPolicy policy) {
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
            if (entry.preAuthorized()) {
                continue;
            }
            var limit = policy.limitFor(entry.accountType(), entry.amount().currency());
            if (limit.isEmpty() || entry.amount().amount().compareTo(limit.get()) <= 0) {
                continue;
            }
            findings.add(new AuditFinding(entry.txId(), entry.reference(), NAME, severity(entry.amount().amount(), limit.get()),
                    "Amount " + entry.amount().amount().toPlainString() + " " + entry.amount().currencyCode()
                            + " exceeds the " + entry.accountType() + " limit of " + limit.get().toPlainString()
                            + " without prior authorization"));
        }
        return findings;
    }

    private static Severity severity(BigDecimal amount, BigDecimal limit) {
        if (amount.compareTo(limit.multiply(FIVE)) >= 0) {
            return Severity.CRITICAL;
        }
        if (amount.compareTo(limit.multiply(TWO)) >= 0) {
            return Severity.HIGH;
        }
        return Severity.MEDIUM;
    }
}
