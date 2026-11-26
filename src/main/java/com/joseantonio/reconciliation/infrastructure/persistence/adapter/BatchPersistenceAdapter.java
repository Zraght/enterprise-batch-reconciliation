package com.joseantonio.reconciliation.infrastructure.persistence.adapter;

import com.joseantonio.reconciliation.application.port.out.BatchRepositoryPort;
import com.joseantonio.reconciliation.application.port.out.ClaimedBatch;
import com.joseantonio.reconciliation.application.port.out.NewBatch;
import com.joseantonio.reconciliation.application.port.out.RegistrationOutcome;
import com.joseantonio.reconciliation.domain.exception.BatchOwnershipLostException;
import com.joseantonio.reconciliation.domain.model.BatchPhase;
import com.joseantonio.reconciliation.domain.model.BatchSnapshot;
import com.joseantonio.reconciliation.domain.model.FileFormat;
import com.joseantonio.reconciliation.domain.model.SettlementWindow;
import com.joseantonio.reconciliation.infrastructure.persistence.BatchOwnershipGuard;
import com.joseantonio.reconciliation.infrastructure.persistence.SqlStatements;
import com.joseantonio.reconciliation.infrastructure.persistence.TimeConversions;
import com.joseantonio.reconciliation.infrastructure.persistence.entity.ReconciliationBatchEntity;
import com.joseantonio.reconciliation.infrastructure.persistence.repository.SpringDataBatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Batch lifecycle on SQL Server.
 *
 * <p>The lifecycle methods are NOT {@code @Transactional}: each one is a SINGLE atomic statement (auto-commit), which
 * keeps the claim race-free without holding locks and lets the unique-index violation of a busy source be handled
 * cleanly instead of poisoning an outer transaction.</p>
 *
 * <p>The exception is {@link #purgeDerivedData}, which is a sequence of bounded deletions. Each step runs in its own short
 * transaction that STARTS by taking the batch row lock with the ownership check. SQL Server keeps that exclusive row lock
 * until the transaction ends, and both the claim of a new attempt and the release of a stale batch need that same lock, so
 * ownership cannot change between the check and the deletion: a worker whose attempt became obsolete deletes nothing, and
 * a worker that still owns the batch cannot be displaced in the middle of a step.</p>
 */
@Component
public class BatchPersistenceAdapter implements BatchRepositoryPort {

    private static final Logger log = LoggerFactory.getLogger(BatchPersistenceAdapter.class);

    /** Children first: results reference settlements and ledger; settlements are referenced by results only. */
    private static final List<String> PURGE_STEPS = List.of(
            SqlStatements.PURGE_ALERTS,
            SqlStatements.PURGE_REOPEN_RESOLVED,
            SqlStatements.PURGE_RESULTS,
            SqlStatements.PURGE_INVALID_RECORDS,
            SqlStatements.PURGE_SETTLEMENTS,
            SqlStatements.PURGE_LEDGER_MARKS);

    private final SpringDataBatchRepository repository;
    private final JdbcTemplate jdbc;
    private final BatchOwnershipGuard guard;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public BatchPersistenceAdapter(SpringDataBatchRepository repository, JdbcTemplate jdbc, BatchOwnershipGuard guard,
                                   TransactionTemplate transaction, Clock clock) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.guard = guard;
        this.transaction = transaction;
        this.clock = clock;
    }

    @Override
    public RegistrationOutcome register(NewBatch newBatch) {
        Optional<ReconciliationBatchEntity> existing =
                repository.findBySourceNameAndFileSha256(newBatch.sourceName(), newBatch.fileSha256());
        if (existing.isPresent()) {
            return new RegistrationOutcome(existing.get().toSnapshot(), false);
        }
        try {
            ReconciliationBatchEntity saved = repository.saveAndFlush(
                    ReconciliationBatchEntity.pending(newBatch, TimeConversions.toUtc(clock.instant())));
            return new RegistrationOutcome(saved.toSnapshot(), true);
        } catch (DataIntegrityViolationException e) {
            // Lost a race against a concurrent upload of the same file: the unique index UX_rb_source_file_hash decided.
            return repository.findBySourceNameAndFileSha256(newBatch.sourceName(), newBatch.fileSha256())
                    .map(found -> new RegistrationOutcome(found.toSnapshot(), false))
                    .orElseThrow(() -> e);
        }
    }

    @Override
    public Optional<BatchSnapshot> findById(long batchId) {
        return repository.findById(batchId).map(ReconciliationBatchEntity::toSnapshot);
    }

    @Override
    public boolean resetForRetry(long batchId, Instant declaredPeriodStart, Instant declaredPeriodEnd) {
        // Typed setters: a bare null in an Object... argument leaves the SQL type undefined for the driver.
        return jdbc.update(SqlStatements.RESET_FOR_RETRY, ps -> {
            setNullableTimestamp(ps, 1, declaredPeriodStart);
            setNullableTimestamp(ps, 2, declaredPeriodEnd);
            ps.setLong(3, batchId);
        }) == 1;
    }

    private static void setNullableTimestamp(java.sql.PreparedStatement ps, int index, Instant value)
            throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, Types.TIMESTAMP);
        } else {
            ps.setObject(index, TimeConversions.toUtc(value));
        }
    }

    @Override
    public List<Long> findPendingBatchIds(int limit) {
        return jdbc.queryForList(SqlStatements.FIND_PENDING_BATCH_IDS, Long.class, limit);
    }

    @Override
    public List<Long> releaseStaleBatches(Duration staleAfter) {
        int seconds = (int) Math.min(Integer.MAX_VALUE, Math.max(1, staleAfter.toSeconds()));
        return jdbc.query(SqlStatements.RELEASE_STALE_BATCHES, (rs, i) -> rs.getLong(1), -seconds);
    }

    @Override
    public Optional<ClaimedBatch> tryClaim(long batchId) {
        try {
            List<ClaimedBatch> claimed = jdbc.query(SqlStatements.CLAIM_BATCH,
                    (rs, i) -> new ClaimedBatch(rs.getLong("batch_id"), rs.getInt("attempt_no"),
                            rs.getString("source_name"), FileFormat.valueOf(rs.getString("file_format")),
                            rs.getString("stored_file_location"), rs.getLong("file_size_bytes"),
                            TimeConversions.toInstant(rs.getObject("declared_period_start", LocalDateTime.class)),
                            TimeConversions.toInstant(rs.getObject("declared_period_end", LocalDateTime.class))),
                    batchId);
            return claimed.stream().findFirst();
        } catch (DataIntegrityViolationException e) {
            // UX_rb_single_processing_per_source: another batch of the same source is PROCESSING.
            log.debug("Batch {} not claimed: its source already has a batch in PROCESSING", batchId);
            return Optional.empty();
        }
    }

    @Override
    public void updateProgress(ClaimedBatch batch, BatchPhase phase, int progressPercent) {
        guard.heartbeat(batch, phase, progressPercent);
    }

    @Override
    public void purgeDerivedData(ClaimedBatch batch) {
        for (String step : PURGE_STEPS) {
            int affected;
            do {
                affected = purgeChunk(batch, step);
            } while (affected >= SqlStatements.PURGE_CHUNK);
        }
        Integer reset = transaction.execute(status -> jdbc.update(SqlStatements.PURGE_RESET_BATCH,
                batch.batchId(), batch.attempt()));
        if (reset == null || reset != 1) {
            throw new BatchOwnershipLostException(batch.batchId(), batch.attempt());
        }
    }

    /**
     * One purge step = one transaction: ownership check first (it takes and keeps the batch row lock), then a bounded
     * deletion. If the lease is lost the check throws before anything is deleted and the transaction rolls back.
     */
    private int purgeChunk(ClaimedBatch batch, String sql) {
        Integer affected = transaction.execute(status -> {
            guard.heartbeat(batch);
            return jdbc.update(sql, batch.batchId());
        });
        return affected == null ? 0 : affected;
    }

    @Override
    public void recordScope(ClaimedBatch batch, SettlementWindow window, Instant dueCutoff) {
        int updated = jdbc.update(SqlStatements.RECORD_SCOPE, TimeConversions.toUtc(window.start()),
                TimeConversions.toUtc(window.end()), window.source().name(), TimeConversions.toUtc(dueCutoff),
                batch.batchId(), batch.attempt());
        requireOwned(batch, updated);
    }

    @Override
    public void recordDeferred(ClaimedBatch batch, int deferredLedgerEntries) {
        int updated = jdbc.update(SqlStatements.RECORD_DEFERRED, deferredLedgerEntries, batch.batchId(), batch.attempt());
        requireOwned(batch, updated);
    }

    @Override
    public void markCompleted(ClaimedBatch batch) {
        requireOwned(batch, jdbc.update(SqlStatements.MARK_COMPLETED, batch.batchId(), batch.attempt()));
    }

    @Override
    public void markFailed(ClaimedBatch batch, String reason) {
        int updated = jdbc.update(SqlStatements.MARK_FAILED, reason, batch.batchId(), batch.attempt());
        if (updated != 1) {
            log.warn("Batch {} attempt {} could not be marked FAILED: lease already lost", batch.batchId(), batch.attempt());
            return;
        }
        try {
            releaseClaims(batch);
        } catch (RuntimeException e) {
            // The batch is FAILED either way; whoever retries it purges these marks first.
            log.error("Batch {} is FAILED but its claims could not be fully released", batch.batchId(), e);
        }
    }

    /**
     * A failed batch gives back the ledger movements it consumed and reopens the discrepancies it had resolved, so it does
     * not block other batches of its source. Same discipline as the purge: every step is a short transaction that first
     * locks the batch row under the "still the failed attempt" predicate.
     */
    private void releaseClaims(ClaimedBatch batch) {
        for (String sql : List.of(SqlStatements.PURGE_LEDGER_MARKS, SqlStatements.PURGE_REOPEN_RESOLVED)) {
            int affected;
            do {
                Integer n = transaction.execute(status -> {
                    if (jdbc.update(SqlStatements.LOCK_FAILED_BATCH, batch.batchId(), batch.attempt()) != 1) {
                        return -1; // retried or re-claimed meanwhile: not ours to release any more
                    }
                    return jdbc.update(sql, batch.batchId());
                });
                affected = n == null ? -1 : n;
            } while (affected >= SqlStatements.PURGE_CHUNK);
        }
    }

    private static void requireOwned(ClaimedBatch batch, int updatedRows) {
        if (updatedRows != 1) {
            throw new BatchOwnershipLostException(batch.batchId(), batch.attempt());
        }
    }
}
