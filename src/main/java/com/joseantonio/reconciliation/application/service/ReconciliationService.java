package com.joseantonio.reconciliation.application.service;

import com.joseantonio.reconciliation.application.port.out.BatchRepositoryPort;
import com.joseantonio.reconciliation.application.port.out.ClaimedBatch;
import com.joseantonio.reconciliation.application.port.out.LedgerPort;
import com.joseantonio.reconciliation.application.port.out.ReconciliationChunk;
import com.joseantonio.reconciliation.application.port.out.ReconciliationResultPort;
import com.joseantonio.reconciliation.application.port.out.SettlementStorePort;
import com.joseantonio.reconciliation.domain.model.ExternalSettlement;
import com.joseantonio.reconciliation.domain.model.LateBooking;
import com.joseantonio.reconciliation.domain.model.LedgerEntry;
import com.joseantonio.reconciliation.domain.model.LedgerStatus;
import com.joseantonio.reconciliation.domain.model.MatchStatus;
import com.joseantonio.reconciliation.domain.model.OutOfScopeLedger;
import com.joseantonio.reconciliation.domain.model.ReconciliationCandidate;
import com.joseantonio.reconciliation.domain.model.ReconciliationOutcome;
import com.joseantonio.reconciliation.domain.model.ReferenceCode;
import com.joseantonio.reconciliation.domain.model.SettlementWindow;
import com.joseantonio.reconciliation.domain.rule.ReconciliationEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 2. See docs/ARCHITECTURE.md ("Reconciliation scope") for the full policy. In short, for a batch of source S with
 * settlement period P and settlement lag L (due cutoff C = P.end - L):
 *
 * <ol>
 *   <li><b>Settlement pass</b> - every settlement of the file is paired with the POSTED, not-yet-consumed movement of
 *       the same source and reference, whatever their dates: MATCHED / AMOUNT_MISMATCH. With no counterpart it is
 *       MISSING_INTERNAL, and the detail says whether that is because the movement exists but is PENDING/REVERSED, or
 *       because the reference was already reconciled by an earlier batch (a settlement repeated across files). Paired
 *       movements are consumed by this batch; earlier MISSING_EXTERNAL discrepancies for them are marked resolved.</li>
 *   <li><b>Late-booking pass</b> - movements booked AFTER their settlement was reported as MISSING_INTERNAL by an earlier
 *       batch are paired with that settlement and the earlier discrepancy is marked resolved.</li>
 *   <li><b>Overdue pass</b> - POSTED, unconsumed movements booked up to C are MISSING_EXTERNAL. They are NOT consumed, so
 *       a later file can still settle them.</li>
 *   <li><b>Deferred</b> - movements booked after C are pending settlement: not reported, not consumed; only counted.</li>
 * </ol>
 * Everything is keyset-paginated: constant memory, one transaction per chunk.
 */
public final class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final int START = 40;
    private static final int SETTLEMENT_PASS_END = 75;
    private static final int LATE_PASS = 76;
    private static final int OVERDUE_PASS = 78;

    private final ReconciliationEngine engine;
    private final SettlementStorePort settlements;
    private final LedgerPort ledger;
    private final ReconciliationResultPort results;
    private final BatchRepositoryPort batches;
    private final ProcessingSettings settings;
    private final ReconciliationPolicy policy;

    public ReconciliationService(ReconciliationEngine engine, SettlementStorePort settlements, LedgerPort ledger,
                                 ReconciliationResultPort results, BatchRepositoryPort batches,
                                 ProcessingSettings settings, ReconciliationPolicy policy) {
        this.engine = engine;
        this.settlements = settlements;
        this.ledger = ledger;
        this.results = results;
        this.batches = batches;
        this.settings = settings;
        this.policy = policy;
    }

    /**
     * @param earliest earliest settlement timestamp of the valid records (used when no period was declared)
     * @param latest   latest settlement timestamp of the valid records
     */
    public void reconcile(ClaimedBatch batch, int validSettlements, Instant earliest, Instant latest) {
        SettlementWindow window = batch.declaredPeriodStart() != null && batch.declaredPeriodEnd() != null
                ? SettlementWindow.declared(batch.declaredPeriodStart(), batch.declaredPeriodEnd())
                : SettlementWindow.inferred(earliest, latest);
        Instant dueCutoff = window.dueCutoff(policy.settlementLag());
        batches.recordScope(batch, window, dueCutoff);
        log.info("Settlement period {} ({}), lag {}, due cutoff {}", window.start() + ".." + window.end(),
                window.source(), policy.settlementLag(), dueCutoff);

        settlementPass(batch, validSettlements);
        latePass(batch);
        overduePass(batch, dueCutoff);

        int deferred = ledger.countDeferred(batch.sourceName(), dueCutoff);
        batches.recordDeferred(batch, deferred);
        log.info("{} ledger movements are still within the settlement lag (deferred, not reported)", deferred);
    }

    // ------------------------------------------------------------------ 1. settlements of the file

    private void settlementPass(ClaimedBatch batch, int validSettlements) {
        long after = 0;
        long processed = 0;
        while (true) {
            List<ExternalSettlement> page = settlements.findAfter(batch.batchId(), after, settings.chunkSize());
            if (page.isEmpty()) {
                break;
            }
            long upTo = page.get(page.size() - 1).settlementId();
            Map<ReferenceCode, LedgerEntry> inScope = new HashMap<>();
            for (LedgerEntry entry : ledger.findScopeForSettlements(batch.batchId(), batch.sourceName(), after, upTo)) {
                if (inScope.putIfAbsent(entry.reference(), entry) != null) {
                    throw new IllegalStateException("More than one POSTED ledger movement for reference "
                            + entry.reference() + " in source " + batch.sourceName());
                }
            }
            List<ReconciliationOutcome> outcomes = new ArrayList<>(page.size());
            Set<Long> consumed = new HashSet<>();
            boolean hasOrphans = false;
            for (ExternalSettlement s : page) {
                LedgerEntry match = inScope.get(s.externalRef());
                outcomes.add(engine.reconcile(new ReconciliationCandidate(s.externalRef(), match, s)));
                if (match != null) {
                    consumed.add(match.txId());
                } else {
                    hasOrphans = true;
                }
            }
            if (hasOrphans) {
                outcomes = explainOrphans(batch, after, upTo, outcomes);
            }
            processed += page.size();
            int progress = START + (int) Math.min(SETTLEMENT_PASS_END - START,
                    (long) (SETTLEMENT_PASS_END - START) * processed / Math.max(1, validSettlements));
            results.saveChunk(batch, new ReconciliationChunk(outcomes, consumed, progress));
            after = upTo;
        }
        log.info("Settlement pass finished: {} settlements reconciled", processed);
    }

    /** Adds to each MISSING_INTERNAL outcome the reason why the movement was not eligible, when one exists. */
    private List<ReconciliationOutcome> explainOrphans(ClaimedBatch batch, long after, long upTo,
                                                       List<ReconciliationOutcome> outcomes) {
        Map<ReferenceCode, OutOfScopeLedger> byReference = new HashMap<>();
        for (OutOfScopeLedger o : ledger.findOutOfScopeForSettlements(batch.batchId(), batch.sourceName(), after, upTo)) {
            // A movement consumed by another batch is the most informative explanation: prefer it.
            byReference.merge(o.entry().reference(), o,
                    (a, b) -> a.reconciledBatchId() != null ? a : b);
        }
        List<ReconciliationOutcome> explained = new ArrayList<>(outcomes.size());
        for (ReconciliationOutcome outcome : outcomes) {
            OutOfScopeLedger why = outcome.status() == MatchStatus.MISSING_INTERNAL
                    ? byReference.get(outcome.reference()) : null;
            explained.add(why == null ? outcome : outcome.withDetail(outcome.detail() + "; " + describe(why)));
        }
        return explained;
    }

    static String describe(OutOfScopeLedger why) {
        if (why.reconciledBatchId() != null) {
            return "ALREADY_RECONCILED: reference was already reconciled by batch " + why.reconciledBatchId()
                    + " (ledger movement " + why.entry().txId() + "); this settlement is a repeat across files";
        }
        LedgerStatus status = why.entry().status();
        return "LEDGER_NOT_POSTED: ledger movement " + why.entry().txId() + " exists with status " + status
                + " and is not eligible for reconciliation";
    }

    // ------------------------------------------------------------------ 2. movements booked after their settlement

    private void latePass(ClaimedBatch batch) {
        long afterTx = 0;
        long paired = 0;
        while (true) {
            List<LateBooking> page = ledger.findLateBookings(batch.batchId(), batch.sourceName(), afterTx, settings.chunkSize());
            if (page.isEmpty()) {
                break;
            }
            List<ReconciliationOutcome> outcomes = new ArrayList<>(page.size());
            Set<Long> consumed = new HashSet<>();
            Set<Long> resolved = new HashSet<>();
            for (LateBooking late : page) {
                ReconciliationOutcome outcome = engine.reconcile(
                        new ReconciliationCandidate(late.ledger().reference(), late.ledger(), late.settlement()));
                outcomes.add(outcome.withDetail("LATE_BOOKING: the settlement was received by batch " + late.originBatchId()
                        + " before the ledger movement was booked; closes that MISSING_INTERNAL. " + outcome.detail()));
                consumed.add(late.ledger().txId());
                resolved.add(late.settlement().settlementId());
            }
            paired += page.size();
            results.saveChunk(batch, new ReconciliationChunk(outcomes, consumed, resolved, LATE_PASS));
            afterTx = page.get(page.size() - 1).ledger().txId();
        }
        log.info("Late-booking pass finished: {} earlier discrepancies resolved", paired);
    }

    // ------------------------------------------------------------------ 3. movements that should already have settled

    private void overduePass(ClaimedBatch batch, Instant dueCutoff) {
        long afterTx = 0;
        long overdue = 0;
        while (true) {
            List<LedgerEntry> page = ledger.findDueUnreconciledAfter(batch.sourceName(), dueCutoff, afterTx, settings.chunkSize());
            if (page.isEmpty()) {
                break;
            }
            List<ReconciliationOutcome> outcomes = new ArrayList<>(page.size());
            for (LedgerEntry entry : page) {
                outcomes.add(engine.reconcile(new ReconciliationCandidate(entry.reference(), entry, null)));
            }
            overdue += page.size();
            results.saveChunk(batch, new ReconciliationChunk(outcomes, Set.of(), OVERDUE_PASS));
            afterTx = page.get(page.size() - 1).txId();
        }
        log.info("Overdue pass finished: {} ledger movements are overdue (booked up to {}) and absent from the file",
                overdue, dueCutoff);
    }
}
