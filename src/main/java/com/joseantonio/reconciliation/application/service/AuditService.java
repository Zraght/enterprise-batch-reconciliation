package com.joseantonio.reconciliation.application.service;

import com.joseantonio.reconciliation.application.port.out.AuditAlertPort;
import com.joseantonio.reconciliation.application.port.out.BatchRepositoryPort;
import com.joseantonio.reconciliation.application.port.out.ClaimedBatch;
import com.joseantonio.reconciliation.application.port.out.LedgerPort;
import com.joseantonio.reconciliation.domain.model.AccountActivity;
import com.joseantonio.reconciliation.domain.model.AuditAlert;
import com.joseantonio.reconciliation.domain.model.AuditFinding;
import com.joseantonio.reconciliation.domain.model.BatchPhase;
import com.joseantonio.reconciliation.domain.model.LedgerEntry;
import com.joseantonio.reconciliation.domain.rule.AuditEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 3: audits, account by account, every ledger movement that took part in the batch and stores the alerts in chunks.
 *
 * <p><b>History across batches.</b> Accounts are evaluated in groups. For each group the movements of the same accounts
 * booked within the rules' look-back window are loaded from the whole ledger (any batch, source or state) and given to
 * the rules as context ({@link AccountActivity#history()}). A temporal duplicate whose twin was reconciled in another
 * batch is thus detected. The alert is raised once per (movement, rule) in the whole system: the storage refuses a
 * second alert for the same movement and rule coming from another batch.</p>
 *
 * <p>Keeps the batch lease alive (heartbeat) even when long stretches of accounts produce no alert.</p>
 */
public final class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final int AUDIT_PROGRESS = 90;
    /** Accounts per history query (the IN list must stay well below SQL Server's 2100 parameter limit). */
    static final int ACCOUNTS_PER_HISTORY_QUERY = 500;

    private final AuditEngine engine;
    private final LedgerPort ledger;
    private final AuditAlertPort alerts;
    private final BatchRepositoryPort batches;
    private final ProcessingSettings settings;
    private final Clock clock;

    public AuditService(AuditEngine engine, LedgerPort ledger, AuditAlertPort alerts, BatchRepositoryPort batches,
                        ProcessingSettings settings, Clock clock) {
        this.engine = engine;
        this.ledger = ledger;
        this.alerts = alerts;
        this.batches = batches;
        this.settings = settings;
        this.clock = clock;
    }

    /** @return number of alerts produced (before de-duplication in storage) */
    public long audit(ClaimedBatch batch) {
        Accumulator acc = new Accumulator();
        ledger.forEachAuditActivity(batch.batchId(), activity -> {
            acc.pending.add(activity);
            acc.pendingEntries += activity.entries().size();
            acc.entriesSeen += activity.entries().size();
            if (acc.pending.size() >= ACCOUNTS_PER_HISTORY_QUERY || acc.pendingEntries >= settings.chunkSize()) {
                evaluatePending(batch, acc);
            }
            if (acc.alertBuffer.size() >= settings.chunkSize()) {
                flushAlerts(batch, acc);
            } else if (acc.entriesSeen - acc.lastHeartbeatAt >= settings.chunkSize()) {
                batches.updateProgress(batch, BatchPhase.AUDIT, AUDIT_PROGRESS);
                acc.lastHeartbeatAt = acc.entriesSeen;
            }
        });
        evaluatePending(batch, acc);
        flushAlerts(batch, acc);
        log.info("Audit finished: {} movements audited, {} alerts", acc.entriesSeen, acc.produced);
        return acc.produced;
    }

    private void evaluatePending(ClaimedBatch batch, Accumulator acc) {
        if (acc.pending.isEmpty()) {
            return;
        }
        Map<String, List<LedgerEntry>> historyByAccount = loadHistory(acc.pending);
        for (AccountActivity activity : acc.pending) {
            AccountActivity withHistory = new AccountActivity(activity.accountNumber(), activity.entries(),
                    historyByAccount.getOrDefault(activity.accountNumber(), List.of()));
            for (AuditFinding f : engine.audit(withHistory)) {
                acc.alertBuffer.add(new AuditAlert(batch.batchId(), f.ledgerTxId(), f.reference(), f.rule(),
                        f.severity(), f.description(), clock.instant()));
            }
        }
        acc.pending.clear();
        acc.pendingEntries = 0;
    }

    private Map<String, List<LedgerEntry>> loadHistory(List<AccountActivity> pending) {
        Duration lookback = engine.historyLookback();
        if (lookback.isZero()) {
            return Map.of();
        }
        Instant min = null;
        Instant max = null;
        List<String> accounts = new ArrayList<>(pending.size());
        for (AccountActivity a : pending) {
            accounts.add(a.accountNumber());
            for (LedgerEntry e : a.entries()) {
                min = min == null || e.timestamp().isBefore(min) ? e.timestamp() : min;
                max = max == null || e.timestamp().isAfter(max) ? e.timestamp() : max;
            }
        }
        Map<String, List<LedgerEntry>> grouped = new HashMap<>();
        for (LedgerEntry h : ledger.findAccountHistory(accounts, min.minus(lookback), max)) {
            grouped.computeIfAbsent(h.accountNumber(), k -> new ArrayList<>()).add(h);
        }
        return grouped;
    }

    private void flushAlerts(ClaimedBatch batch, Accumulator acc) {
        if (!acc.alertBuffer.isEmpty()) {
            alerts.saveAll(batch, List.copyOf(acc.alertBuffer));
            acc.produced += acc.alertBuffer.size();
            acc.alertBuffer.clear();
        }
        acc.lastHeartbeatAt = acc.entriesSeen;
    }

    private static final class Accumulator {
        final List<AccountActivity> pending = new ArrayList<>();
        final List<AuditAlert> alertBuffer = new ArrayList<>();
        int pendingEntries;
        long entriesSeen;
        long lastHeartbeatAt;
        long produced;
    }
}
