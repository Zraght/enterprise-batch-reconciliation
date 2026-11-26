package com.joseantonio.reconciliation.domain.rule;

import com.joseantonio.reconciliation.domain.model.AccountActivity;
import com.joseantonio.reconciliation.domain.model.AuditFinding;

import java.time.Duration;
import java.util.List;

/** Strategy for deterministic transaction auditing, evaluated per account (entries in chronological order). */
public interface AuditRule {

    String name();

    List<AuditFinding> evaluate(AccountActivity activity);

    /**
     * How far back in time before an audited movement this rule needs to look at OTHER movements of the same account
     * (history). {@link Duration#ZERO} for rules that judge each movement on its own.
     */
    default Duration lookback() {
        return Duration.ZERO;
    }
}
