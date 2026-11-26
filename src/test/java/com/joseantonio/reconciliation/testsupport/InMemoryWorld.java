package com.joseantonio.reconciliation.testsupport;

import com.joseantonio.reconciliation.application.port.in.DiscrepancyFilter;
import com.joseantonio.reconciliation.application.port.in.DiscrepancyView;
import com.joseantonio.reconciliation.application.port.in.PageQuery;
import com.joseantonio.reconciliation.application.port.in.PageResult;
import com.joseantonio.reconciliation.application.port.out.AuditAlertPort;
import com.joseantonio.reconciliation.application.port.out.BatchDispatchPort;
import com.joseantonio.reconciliation.application.port.out.BatchRepositoryPort;
import com.joseantonio.reconciliation.application.port.out.ClaimedBatch;
import com.joseantonio.reconciliation.application.port.out.IngestionChunk;
import com.joseantonio.reconciliation.application.port.out.LedgerPort;
import com.joseantonio.reconciliation.application.port.out.NewBatch;
import com.joseantonio.reconciliation.application.port.out.ReconciliationChunk;
import com.joseantonio.reconciliation.application.port.out.ReconciliationResultPort;
import com.joseantonio.reconciliation.application.port.out.RegistrationOutcome;
import com.joseantonio.reconciliation.application.port.out.SettlementStorePort;
import com.joseantonio.reconciliation.domain.exception.BatchOwnershipLostException;
import com.joseantonio.reconciliation.domain.exception.ReconciliationConflictException;
import com.joseantonio.reconciliation.domain.model.AccountActivity;
import com.joseantonio.reconciliation.domain.model.AuditAlert;
import com.joseantonio.reconciliation.domain.model.BatchPhase;
import com.joseantonio.reconciliation.domain.model.BatchSnapshot;
import com.joseantonio.reconciliation.domain.model.BatchStatus;
import com.joseantonio.reconciliation.domain.model.ExternalSettlement;
import com.joseantonio.reconciliation.domain.model.FileFormat;
import com.joseantonio.reconciliation.domain.model.InvalidRecord;
import com.joseantonio.reconciliation.domain.model.LateBooking;
import com.joseantonio.reconciliation.domain.model.LedgerEntry;
import com.joseantonio.reconciliation.domain.model.LedgerStatus;
import com.joseantonio.reconciliation.domain.model.MatchStatus;
import com.joseantonio.reconciliation.domain.model.OutOfScopeLedger;
import com.joseantonio.reconciliation.domain.model.ReconciliationOutcome;
import com.joseantonio.reconciliation.domain.model.SettlementWindow;
import com.joseantonio.reconciliation.domain.model.Severity;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-memory implementation of every persistence port with the SAME guarantees as the SQL adapters: atomic claim, one
 * PROCESSING batch per source, attempt fencing on every write (including the purge), unique constraints, compare-and-set
 * consumption of ledger entries, cross-batch resolution of discrepancies and global de-duplication of alerts per
 * (ledger movement, rule). Every state change is synchronized on the world, which stands for "one transaction".
 * Fault injection hooks let tests simulate persistence failures.
 */
public final class InMemoryWorld implements BatchRepositoryPort, SettlementStorePort, LedgerPort,
        ReconciliationResultPort, AuditAlertPort, BatchDispatchPort {

    public static final class Row {
        public long id;
        public Instant executionDate;
        public String source;
        public String fileName;
        public FileFormat format;
        public String sha;
        public long size;
        public String location;
        public Instant declaredStart;
        public Instant declaredEnd;
        public SettlementWindow window;
        public Instant dueCutoff;
        public Integer deferred;
        public BatchStatus status = BatchStatus.PENDING;
        public BatchPhase phase = BatchPhase.QUEUED;
        public int progress;
        public int attempt;
        public int total;
        public int valid;
        public int invalid;
        public String failure;
        public Instant heartbeat;
        public Instant created;
        public Instant started;
        public Instant completed;
    }

    private static final class ResultRow {
        final long id;
        final long batchId;
        final ReconciliationOutcome outcome;
        Long resolvedBy;

        ResultRow(long id, long batchId, ReconciliationOutcome outcome) {
            this.id = id;
            this.batchId = batchId;
            this.outcome = outcome;
        }
    }

    private final Clock clock;
    private long batchSeq;
    private long settlementSeq;
    private long ledgerSeq;
    private long resultSeq;
    private final Map<Long, Row> batches = new LinkedHashMap<>();
    private final Map<Long, ExternalSettlement> settlements = new LinkedHashMap<>();
    private final Map<Long, Long> settlementBatch = new HashMap<>();
    private final Map<Long, LedgerEntry> ledger = new LinkedHashMap<>();
    private final Map<Long, Long> ledgerReconciledBy = new HashMap<>();
    private final Map<Long, List<InvalidRecord>> invalidByBatch = new HashMap<>();
    private final Map<Long, Map<String, ResultRow>> resultsByBatch = new HashMap<>();
    private final Map<Long, Map<String, AuditAlert>> alertsByBatch = new HashMap<>();
    private final Map<String, Long> alertOwners = new HashMap<>();
    public final List<Long> dispatched = new CopyOnWriteArrayList<>();

    /** Fault injection: runs at the start of every reconciliation chunk write; may throw to simulate a failure. */
    public volatile Runnable beforeResultChunk = () -> { };
    /** Fault injection for the ingestion chunk write. */
    public volatile Runnable beforeIngestionChunk = () -> { };
    /** Fault injection for the alert write. */
    public volatile Runnable beforeAlertWrite = () -> { };

    public InMemoryWorld(Clock clock) {
        this.clock = clock;
    }

    // ---------------------------------------------------------------- helpers for tests
    public synchronized Row row(long batchId) {
        return batches.get(batchId);
    }

    public synchronized List<ReconciliationOutcome> results(long batchId) {
        return resultsByBatch.getOrDefault(batchId, Map.of()).values().stream().map(r -> r.outcome).toList();
    }

    public synchronized ReconciliationOutcome result(long batchId, String reference) {
        ResultRow r = resultsByBatch.getOrDefault(batchId, Map.of()).get(reference);
        return r == null ? null : r.outcome;
    }

    public synchronized Long resolvedBy(long batchId, String reference) {
        ResultRow r = resultsByBatch.getOrDefault(batchId, Map.of()).get(reference);
        return r == null ? null : r.resolvedBy;
    }

    public synchronized List<AuditAlert> alerts(long batchId) {
        return new ArrayList<>(alertsByBatch.getOrDefault(batchId, Map.of()).values());
    }

    public synchronized List<AuditAlert> allAlerts() {
        List<AuditAlert> all = new ArrayList<>();
        alertsByBatch.values().forEach(m -> all.addAll(m.values()));
        return all;
    }

    public synchronized List<InvalidRecord> invalidRecords(long batchId) {
        return new ArrayList<>(invalidByBatch.getOrDefault(batchId, List.of()));
    }

    public synchronized int settlementCount(long batchId) {
        return (int) settlementBatch.values().stream().filter(b -> b == batchId).count();
    }

    public synchronized Long reconciledBy(String reference) {
        for (Map.Entry<Long, LedgerEntry> e : ledger.entrySet()) {
            if (e.getValue().reference().value().equals(reference)) {
                return ledgerReconciledBy.get(e.getKey());
            }
        }
        return null;
    }

    public synchronized int ledgerSize() {
        return ledger.size();
    }

    public synchronized long txIdOf(String reference) {
        return ledger.entrySet().stream().filter(e -> e.getValue().reference().value().equals(reference))
                .findFirst().orElseThrow().getKey();
    }

    // ---------------------------------------------------------------- BatchRepositoryPort
    @Override
    public synchronized RegistrationOutcome register(NewBatch nb) {
        for (Row r : batches.values()) {
            if (r.source.equalsIgnoreCase(nb.sourceName()) && r.sha.equals(nb.fileSha256())) {
                return new RegistrationOutcome(snapshot(r), false);
            }
        }
        Row r = new Row();
        r.id = ++batchSeq;
        r.executionDate = nb.executionDate();
        r.source = nb.sourceName();
        r.fileName = nb.fileName();
        r.format = nb.fileFormat();
        r.sha = nb.fileSha256();
        r.size = nb.fileSizeBytes();
        r.location = nb.storedFileLocation();
        r.declaredStart = nb.declaredPeriodStart();
        r.declaredEnd = nb.declaredPeriodEnd();
        r.created = clock.instant();
        batches.put(r.id, r);
        return new RegistrationOutcome(snapshot(r), true);
    }

    @Override
    public synchronized Optional<BatchSnapshot> findById(long batchId) {
        return Optional.ofNullable(batches.get(batchId)).map(InMemoryWorld::snapshot);
    }

    @Override
    public synchronized boolean resetForRetry(long batchId, Instant declaredStart, Instant declaredEnd) {
        Row r = batches.get(batchId);
        if (r == null || r.status != BatchStatus.FAILED) {
            return false;
        }
        r.status = BatchStatus.PENDING;
        r.phase = BatchPhase.QUEUED;
        r.progress = 0;
        r.failure = null;
        r.started = null;
        r.completed = null;
        r.heartbeat = null;
        r.declaredStart = declaredStart;
        r.declaredEnd = declaredEnd;
        return true;
    }

    @Override
    public synchronized List<Long> findPendingBatchIds(int limit) {
        return batches.values().stream().filter(r -> r.status == BatchStatus.PENDING)
                .sorted(Comparator.comparingLong(r -> r.id)).limit(limit).map(r -> r.id).toList();
    }

    @Override
    public synchronized List<Long> releaseStaleBatches(Duration staleAfter) {
        List<Long> released = new ArrayList<>();
        Instant limit = clock.instant().minus(staleAfter);
        for (Row r : batches.values()) {
            if (r.status == BatchStatus.PROCESSING && r.heartbeat != null && r.heartbeat.isBefore(limit)) {
                r.status = BatchStatus.PENDING;
                r.phase = BatchPhase.QUEUED;
                released.add(r.id);
            }
        }
        return released;
    }

    @Override
    public synchronized Optional<ClaimedBatch> tryClaim(long batchId) {
        Row r = batches.get(batchId);
        if (r == null || r.status != BatchStatus.PENDING) {
            return Optional.empty();
        }
        boolean sourceBusy = batches.values().stream()
                .anyMatch(o -> o.status == BatchStatus.PROCESSING && o.source.equalsIgnoreCase(r.source));
        if (sourceBusy) {
            return Optional.empty();
        }
        r.status = BatchStatus.PROCESSING;
        r.phase = BatchPhase.INGESTION;
        r.progress = 0;
        r.attempt++;
        r.started = clock.instant();
        r.heartbeat = clock.instant();
        r.failure = null;
        return Optional.of(new ClaimedBatch(r.id, r.attempt, r.source, r.format, r.location, r.size,
                r.declaredStart, r.declaredEnd));
    }

    private Row owned(ClaimedBatch b) {
        Row r = batches.get(b.batchId());
        if (r == null || r.status != BatchStatus.PROCESSING || r.attempt != b.attempt()) {
            throw new BatchOwnershipLostException(b.batchId(), b.attempt());
        }
        r.heartbeat = clock.instant();
        return r;
    }

    @Override
    public synchronized void updateProgress(ClaimedBatch batch, BatchPhase phase, int progressPercent) {
        Row r = owned(batch);
        r.phase = phase;
        r.progress = progressPercent;
    }

    /** Ownership check + deletion in one atomic step: a stale worker deletes NOTHING. */
    @Override
    public synchronized void purgeDerivedData(ClaimedBatch batch) {
        Row r = owned(batch);
        long id = batch.batchId();
        alertsByBatch.getOrDefault(id, Map.of()).keySet().forEach(alertOwners::remove);
        alertsByBatch.remove(id);
        resultsByBatch.remove(id);
        // discrepancies of EARLIER batches that this batch's previous attempt had resolved become open again
        resultsByBatch.values().forEach(m -> m.values().forEach(rr -> {
            if (rr.resolvedBy != null && rr.resolvedBy == id) {
                rr.resolvedBy = null;
            }
        }));
        invalidByBatch.remove(id);
        settlements.keySet().removeIf(k -> settlementBatch.get(k) == id);
        settlementBatch.values().removeIf(b -> b == id);
        ledgerReconciledBy.values().removeIf(b -> b == id);
        r.total = 0;
        r.valid = 0;
        r.invalid = 0;
        r.window = null;
        r.dueCutoff = null;
        r.deferred = null;
    }

    @Override
    public synchronized void recordScope(ClaimedBatch batch, SettlementWindow window, Instant dueCutoff) {
        Row r = owned(batch);
        r.window = window;
        r.dueCutoff = dueCutoff;
    }

    @Override
    public synchronized void recordDeferred(ClaimedBatch batch, int deferredLedgerEntries) {
        owned(batch).deferred = deferredLedgerEntries;
    }

    @Override
    public synchronized void markCompleted(ClaimedBatch batch) {
        Row r = owned(batch);
        r.status = BatchStatus.COMPLETED;
        r.phase = BatchPhase.DONE;
        r.progress = 100;
        r.completed = clock.instant();
    }

    @Override
    public synchronized void markFailed(ClaimedBatch batch, String reason) {
        Row r = batches.get(batch.batchId());
        if (r == null || r.status != BatchStatus.PROCESSING || r.attempt != batch.attempt()) {
            return;
        }
        r.status = BatchStatus.FAILED;
        r.failure = reason;
        r.completed = clock.instant();
        // a failed batch releases its claims: consumed ledger movements and the resolutions it had made
        long id = batch.batchId();
        ledgerReconciledBy.values().removeIf(b -> b == id);
        resultsByBatch.values().forEach(m -> m.values().forEach(rr -> {
            if (rr.resolvedBy != null && rr.resolvedBy == id) {
                rr.resolvedBy = null;
            }
        }));
    }

    private static BatchSnapshot snapshot(Row r) {
        return new BatchSnapshot(r.id, r.executionDate, r.source, r.fileName, r.format, r.status, r.phase, r.progress,
                r.total, r.valid, r.invalid, r.failure, r.declaredStart, r.declaredEnd,
                r.window == null ? null : r.window.start(), r.window == null ? null : r.window.end(),
                r.window == null ? null : r.window.source(), r.dueCutoff, r.deferred,
                r.created, r.started, r.completed);
    }

    // ---------------------------------------------------------------- SettlementStorePort
    @Override
    public synchronized void saveChunk(ClaimedBatch batch, IngestionChunk chunk) {
        beforeIngestionChunk.run();
        Row r = owned(batch);
        r.total += chunk.recordsRead();
        r.valid += chunk.valid().size();
        r.invalid += chunk.invalid().size();
        r.progress = chunk.progressPercent();
        r.phase = BatchPhase.INGESTION;
        for (ExternalSettlement s : chunk.valid()) {
            boolean exists = settlements.entrySet().stream().anyMatch(e -> settlementBatch.get(e.getKey()) == batch.batchId()
                    && e.getValue().externalRef().equals(s.externalRef()));
            if (exists) {
                throw new IllegalStateException("unique (batch_id, external_ref) violated: " + s.externalRef());
            }
            long id = ++settlementSeq;
            settlements.put(id, new ExternalSettlement(id, s.externalRef(), s.amount(), s.timestamp(), s.rawPayload(), s.recordNumber()));
            settlementBatch.put(id, batch.batchId());
        }
        invalidByBatch.computeIfAbsent(batch.batchId(), k -> new ArrayList<>()).addAll(chunk.invalid());
    }

    @Override
    public synchronized List<ExternalSettlement> findAfter(long batchId, long settlementIdExclusive, int limit) {
        return settlements.entrySet().stream()
                .filter(e -> settlementBatch.get(e.getKey()) == batchId && e.getKey() > settlementIdExclusive)
                .sorted(Map.Entry.comparingByKey()).limit(limit).map(Map.Entry::getValue).toList();
    }

    @Override
    public synchronized Map<String, Long> invalidCountsByReason(long batchId) {
        Map<String, Long> counts = new TreeMap<>();
        invalidByBatch.getOrDefault(batchId, List.of()).forEach(i -> counts.merge(i.reasonCode(), 1L, Long::sum));
        return counts;
    }

    // ---------------------------------------------------------------- LedgerPort
    @Override
    public synchronized void saveAll(List<LedgerEntry> entries) {
        // unique (source_name, reference_code) WHERE status = 'POSTED'
        Set<String> seen = new HashSet<>();
        for (LedgerEntry e : entries) {
            if (e.status() != LedgerStatus.POSTED) {
                continue;
            }
            String key = e.sourceName().toLowerCase() + "|" + e.reference().value();
            boolean duplicate = !seen.add(key) || ledger.values().stream().anyMatch(o -> o.status() == LedgerStatus.POSTED
                    && o.sourceName().equalsIgnoreCase(e.sourceName()) && o.reference().equals(e.reference()));
            if (duplicate) {
                throw new IllegalStateException("unique POSTED (source_name, reference_code) violated: " + e.reference());
            }
        }
        for (LedgerEntry e : entries) {
            long id = ++ledgerSeq;
            ledger.put(id, new LedgerEntry(id, e.reference(), e.accountNumber(), e.amount(), e.timestamp(), e.status(),
                    e.sourceName(), e.accountType(), e.preAuthorized()));
        }
    }

    private boolean inScope(long txId, LedgerEntry e, String sourceName) {
        return e.sourceName().equalsIgnoreCase(sourceName) && e.status() == LedgerStatus.POSTED
                && !ledgerReconciledBy.containsKey(txId);
    }

    @Override
    public synchronized List<LedgerEntry> findScopeForSettlements(long batchId, String sourceName, long exclusive, long inclusive) {
        List<LedgerEntry> out = new ArrayList<>();
        for (Map.Entry<Long, ExternalSettlement> s : settlements.entrySet()) {
            if (settlementBatch.get(s.getKey()) != batchId || s.getKey() <= exclusive || s.getKey() > inclusive) {
                continue;
            }
            for (Map.Entry<Long, LedgerEntry> l : ledger.entrySet()) {
                if (inScope(l.getKey(), l.getValue(), sourceName) && l.getValue().reference().equals(s.getValue().externalRef())) {
                    out.add(l.getValue());
                }
            }
        }
        return out;
    }

    @Override
    public synchronized List<OutOfScopeLedger> findOutOfScopeForSettlements(long batchId, String sourceName,
                                                                           long exclusive, long inclusive) {
        List<OutOfScopeLedger> out = new ArrayList<>();
        for (Map.Entry<Long, ExternalSettlement> s : settlements.entrySet()) {
            if (settlementBatch.get(s.getKey()) != batchId || s.getKey() <= exclusive || s.getKey() > inclusive) {
                continue;
            }
            for (Map.Entry<Long, LedgerEntry> l : ledger.entrySet()) {
                LedgerEntry e = l.getValue();
                if (e.sourceName().equalsIgnoreCase(sourceName) && e.reference().equals(s.getValue().externalRef())
                        && !inScope(l.getKey(), e, sourceName)) {
                    out.add(new OutOfScopeLedger(e, ledgerReconciledBy.get(l.getKey())));
                }
            }
        }
        return out;
    }

    @Override
    public synchronized List<LateBooking> findLateBookings(long batchId, String sourceName, long txIdExclusive, int limit) {
        List<LateBooking> out = new ArrayList<>();
        ledger.entrySet().stream().filter(l -> l.getKey() > txIdExclusive && inScope(l.getKey(), l.getValue(), sourceName))
                .sorted(Map.Entry.comparingByKey()).forEach(l -> {
                    if (out.size() >= limit) {
                        return;
                    }
                    // latest earlier settlement of the same source and reference with an unresolved MISSING_INTERNAL
                    Long best = null;
                    for (Map.Entry<Long, ExternalSettlement> s : settlements.entrySet()) {
                        long sb = settlementBatch.get(s.getKey());
                        Row origin = batches.get(sb);
                        if (sb == batchId || origin.status != BatchStatus.COMPLETED
                                || !origin.source.equalsIgnoreCase(sourceName)
                                || !s.getValue().externalRef().equals(l.getValue().reference())) {
                            continue;
                        }
                        boolean unresolvedOrphan = resultsByBatch.getOrDefault(sb, Map.of()).values().stream()
                                .anyMatch(rr -> rr.outcome.status() == MatchStatus.MISSING_INTERNAL
                                        && rr.resolvedBy == null && s.getKey().equals(rr.outcome.settlementId()));
                        if (unresolvedOrphan && (best == null || s.getKey() > best)) {
                            best = s.getKey();
                        }
                    }
                    if (best != null) {
                        final Long chosen = best;
                        ResultRow origin = resultsByBatch.get(settlementBatch.get(chosen)).values().stream()
                                .filter(rr -> chosen.equals(rr.outcome.settlementId())).findFirst().orElseThrow();
                        out.add(new LateBooking(l.getValue(), settlements.get(chosen), origin.batchId, origin.id));
                    }
                });
        return out;
    }

    @Override
    public synchronized List<LedgerEntry> findDueUnreconciledAfter(String sourceName, Instant dueCutoff, long txIdExclusive, int limit) {
        return ledger.entrySet().stream()
                .filter(l -> l.getKey() > txIdExclusive && inScope(l.getKey(), l.getValue(), sourceName)
                        && !l.getValue().timestamp().isAfter(dueCutoff))
                .sorted(Map.Entry.comparingByKey()).limit(limit).map(Map.Entry::getValue).toList();
    }

    @Override
    public synchronized int countDeferred(String sourceName, Instant dueCutoff) {
        return (int) ledger.entrySet().stream()
                .filter(l -> inScope(l.getKey(), l.getValue(), sourceName) && l.getValue().timestamp().isAfter(dueCutoff)).count();
    }

    @Override
    public void forEachAuditActivity(long batchId, Consumer<AccountActivity> consumer) {
        Map<String, List<LedgerEntry>> byAccount = new TreeMap<>();
        synchronized (this) {
            for (ResultRow rr : resultsByBatch.getOrDefault(batchId, Map.of()).values()) {
                if (rr.outcome.ledgerTxId() != null) {
                    LedgerEntry e = ledger.get(rr.outcome.ledgerTxId());
                    byAccount.computeIfAbsent(e.accountNumber(), k -> new ArrayList<>()).add(e);
                }
            }
        }
        byAccount.forEach((account, entries) -> consumer.accept(new AccountActivity(account, entries)));
    }

    @Override
    public synchronized List<LedgerEntry> findAccountHistory(Collection<String> accountNumbers, Instant from, Instant to) {
        return ledger.values().stream()
                .filter(e -> e.status() == LedgerStatus.POSTED && accountNumbers.contains(e.accountNumber())
                        && !e.timestamp().isBefore(from) && !e.timestamp().isAfter(to))
                .toList();
    }

    // ---------------------------------------------------------------- ReconciliationResultPort
    @Override
    public synchronized void saveChunk(ClaimedBatch batch, ReconciliationChunk chunk) {
        beforeResultChunk.run();
        Row r = owned(batch);
        r.phase = BatchPhase.RECONCILIATION;
        r.progress = chunk.progressPercent();
        Map<String, ResultRow> results = resultsByBatch.computeIfAbsent(batch.batchId(), k -> new LinkedHashMap<>());
        // all checks first: the chunk is applied completely or not at all
        for (Long txId : chunk.consumedLedgerTxIds()) {
            if (ledgerReconciledBy.containsKey(txId)) {
                throw new ReconciliationConflictException("Ledger entry " + txId + " was already reconciled");
            }
        }
        Set<String> inChunk = new HashSet<>();
        for (ReconciliationOutcome o : chunk.outcomes()) {
            if (results.containsKey(o.reference().value()) || !inChunk.add(o.reference().value())) {
                throw new IllegalStateException("unique (batch_id, reference_code) violated: " + o.reference());
            }
        }
        for (ReconciliationOutcome o : chunk.outcomes()) {
            results.put(o.reference().value(), new ResultRow(++resultSeq, batch.batchId(), o));
        }
        chunk.consumedLedgerTxIds().forEach(id -> ledgerReconciledBy.put(id, batch.batchId()));
        // cross-batch resolution
        resultsByBatch.forEach((otherBatch, rows) -> {
            if (otherBatch == batch.batchId()) {
                return;
            }
            rows.values().forEach(rr -> {
                if (rr.resolvedBy != null) {
                    return;
                }
                boolean lateSettlement = rr.outcome.status() == MatchStatus.MISSING_EXTERNAL
                        && rr.outcome.ledgerTxId() != null && chunk.consumedLedgerTxIds().contains(rr.outcome.ledgerTxId());
                boolean lateBooking = rr.outcome.status() == MatchStatus.MISSING_INTERNAL
                        && rr.outcome.settlementId() != null && chunk.resolvedSettlementIds().contains(rr.outcome.settlementId());
                if (lateSettlement || lateBooking) {
                    rr.resolvedBy = batch.batchId();
                }
            });
        });
    }

    @Override
    public synchronized Map<MatchStatus, Long> countByStatus(long batchId) {
        Map<MatchStatus, Long> m = new EnumMap<>(MatchStatus.class);
        resultsByBatch.getOrDefault(batchId, Map.of()).values().forEach(rr -> m.merge(rr.outcome.status(), 1L, Long::sum));
        return m;
    }

    @Override
    public synchronized long countResolved(long batchId) {
        return resultsByBatch.getOrDefault(batchId, Map.of()).values().stream()
                .filter(rr -> rr.outcome.isDiscrepancy() && rr.resolvedBy != null).count();
    }

    @Override
    public synchronized Map<String, BigDecimal> discrepancyTotalsByCurrency(long batchId) {
        Map<String, BigDecimal> m = new TreeMap<>();
        resultsByBatch.getOrDefault(batchId, Map.of()).values().stream()
                .filter(rr -> rr.outcome.isDiscrepancy() && rr.outcome.discrepancyAmount() != null)
                .forEach(rr -> m.merge(rr.outcome.currency().getCurrencyCode(), rr.outcome.discrepancyAmount().abs(), BigDecimal::add));
        return m;
    }

    @Override
    public synchronized PageResult<DiscrepancyView> findDiscrepancies(long batchId, DiscrepancyFilter f, PageQuery page) {
        List<DiscrepancyView> all = new ArrayList<>();
        for (ResultRow rr : resultsByBatch.getOrDefault(batchId, Map.of()).values()) {
            ReconciliationOutcome o = rr.outcome;
            if (!o.isDiscrepancy()) {
                continue;
            }
            if (f.matchStatus() != null && o.status() != f.matchStatus()) {
                continue;
            }
            if (f.referenceContains() != null && !o.reference().value().contains(f.referenceContains())) {
                continue;
            }
            String cur = o.currency() == null ? null : o.currency().getCurrencyCode();
            if (f.currency() != null && !f.currency().equals(cur)) {
                continue;
            }
            if (f.resolved() != null && f.resolved() != (rr.resolvedBy != null)) {
                continue;
            }
            all.add(new DiscrepancyView(rr.id, o.reference().value(), o.status(), o.discrepancyAmount(), cur,
                    o.ledgerTxId(), o.settlementId(), o.detail(), rr.resolvedBy));
        }
        all.sort(Comparator.comparingLong(DiscrepancyView::resultId));
        int from = Math.min(all.size(), page.page() * page.size());
        int to = Math.min(all.size(), from + page.size());
        return PageResult.of(all.subList(from, to), page, all.size());
    }

    // ---------------------------------------------------------------- AuditAlertPort
    @Override
    public synchronized void saveAll(ClaimedBatch batch, List<AuditAlert> alerts) {
        beforeAlertWrite.run();
        owned(batch);
        Map<String, AuditAlert> stored = alertsByBatch.computeIfAbsent(batch.batchId(), k -> new LinkedHashMap<>());
        for (AuditAlert a : alerts) {
            // natural key: a ledger movement raises each rule at most once in its whole life, whatever the batch
            String key = a.ledgerTxId() != null ? "T" + a.ledgerTxId() + "|" + a.rule()
                    : "B" + a.batchId() + "|" + a.reference().value() + "|" + a.rule();
            if (!alertOwners.containsKey(key)) {
                alertOwners.put(key, batch.batchId());
                stored.put(key, a);
            }
        }
    }

    @Override
    public synchronized Map<Severity, Long> countBySeverity(long batchId) {
        Map<Severity, Long> m = new EnumMap<>(Severity.class);
        alertsByBatch.getOrDefault(batchId, Map.of()).values().forEach(a -> m.merge(a.severity(), 1L, Long::sum));
        return m;
    }

    // ---------------------------------------------------------------- BatchDispatchPort
    @Override
    public boolean dispatch(long batchId) {
        dispatched.add(batchId);
        return true;
    }
}
