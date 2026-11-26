package com.joseantonio.reconciliation.domain.rule;

import com.joseantonio.reconciliation.domain.model.AccountActivity;
import com.joseantonio.reconciliation.domain.model.AuditFinding;
import com.joseantonio.reconciliation.domain.model.LedgerEntry;
import com.joseantonio.reconciliation.domain.model.Severity;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Temporal Duplicate Detection: same account, same amount and currency, executed less than {@code window} apart
 * (strictly: a gap equal to the window is NOT a duplicate).
 *
 * <p>The LATER movement of a pair is flagged and its description names the earlier one, so each pair produces exactly
 * one finding and a movement is never flagged for being the earlier one.</p>
 *
 * <p><b>History across batches.</b> The movements being audited are compared with the whole timeline of the account
 * ({@link AccountActivity#timeline()}), which also contains movements reconciled by other batches or still pending.
 * A pair whose halves belong to different batches is therefore caught by whichever batch audits the later half, and
 * only by that one. Movements that exist only as history are never flagged by this audit run.</p>
 */
public final class TemporalDuplicateRule implements AuditRule {

    public static final String NAME = "TEMPORAL_DUPLICATE";

    private final Duration window;

    public TemporalDuplicateRule(Duration window) {
        Objects.requireNonNull(window, "window");
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Duplicate window must be positive");
        }
        this.window = window;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Duration lookback() {
        return window;
    }

    @Override
    public List<AuditFinding> evaluate(AccountActivity activity) {
        List<LedgerEntry> timeline = activity.timeline();
        Set<String> audited = activity.auditedIdentities();
        List<AuditFinding> findings = new ArrayList<>();
        for (int i = 1; i < timeline.size(); i++) {
            LedgerEntry current = timeline.get(i);
            if (!audited.contains(current.identity())) {
                continue; // history only provides context
            }
            // Sorted timeline: walking backwards the gap only grows, stop at the first movement outside the window.
            for (int j = i - 1; j >= 0; j--) {
                LedgerEntry previous = timeline.get(j);
                Duration gap = Duration.between(previous.timestamp(), current.timestamp());
                if (gap.compareTo(window) >= 0) {
                    break;
                }
                if (previous.identity().equals(current.identity())) {
                    continue;
                }
                if (previous.amount().sameValue(current.amount())) {
                    findings.add(new AuditFinding(current.txId(), current.reference(), NAME, Severity.HIGH,
                            "Possible duplicate of " + previous.reference() + ": same account "
                                    + activity.accountNumber() + ", amount " + current.amount().amount().toPlainString()
                                    + " " + current.amount().currencyCode() + ", " + gap.toMillis() / 1000.0
                                    + "s apart (window " + window.toSeconds() + "s)"));
                    break;
                }
            }
        }
        return findings;
    }
}
