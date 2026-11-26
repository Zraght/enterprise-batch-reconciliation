package com.joseantonio.reconciliation.application.port.out;

import com.joseantonio.reconciliation.domain.model.BatchPhase;
import com.joseantonio.reconciliation.domain.model.BatchSnapshot;
import com.joseantonio.reconciliation.domain.model.SettlementWindow;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Lifecycle, leasing and recovery of reconciliation batches. Every method is atomic. Every method that receives a
 * {@link ClaimedBatch} is FENCED: it throws BatchOwnershipLostException, having changed nothing, when the lease is no
 * longer the caller's.
 */
public interface BatchRepositoryPort {

    /** Creates the batch, or returns the existing one for the same (source, file hash). Race-safe. */
    RegistrationOutcome register(NewBatch newBatch);

    Optional<BatchSnapshot> findById(long batchId);

    /**
     * FAILED -> PENDING, replacing the declared settlement period. Returns false if the batch was not FAILED (someone
     * else already retried it).
     */
    boolean resetForRetry(long batchId, Instant declaredPeriodStart, Instant declaredPeriodEnd);

    List<Long> findPendingBatchIds(int limit);

    /** PROCESSING with no heartbeat for {@code staleAfter} -> PENDING. Returns the released batch ids. */
    List<Long> releaseStaleBatches(Duration staleAfter);

    /**
     * Atomically PENDING -> PROCESSING and increments the attempt. Empty when the batch is not PENDING or when
     * another batch of the same source is already PROCESSING (only one reconciliation per source at a time).
     */
    Optional<ClaimedBatch> tryClaim(long batchId);

    /** Heartbeat + progress. */
    void updateProgress(ClaimedBatch batch, BatchPhase phase, int progressPercent);

    /**
     * Removes everything a previous attempt derived (settlements, results, alerts, invalid rows, ledger marks, scope).
     * The ownership check and each deletion step run in ONE transaction that holds the batch row lock, so ownership
     * cannot change between the check and the delete: a stale worker deletes nothing.
     */
    void purgeDerivedData(ClaimedBatch batch);

    /** Stores the effective settlement period and the due cutoff used by this attempt. */
    void recordScope(ClaimedBatch batch, SettlementWindow window, Instant dueCutoff);

    /** Stores how many ledger movements were left pending settlement (not yet due). */
    void recordDeferred(ClaimedBatch batch, int deferredLedgerEntries);

    void markCompleted(ClaimedBatch batch);

    /**
     * Marks the batch FAILED. Best effort: silently ignored when the lease is already lost.
     *
     * <p>A failed batch must not keep other batches of its source hostage, so it also RELEASES its claims: the ledger
     * movements it had consumed become available again and the discrepancies it had resolved in earlier batches are
     * reopened. Its own partial results and invalid records stay for diagnosis. The release is fenced like every write:
     * if the batch is retried and re-claimed meanwhile, the release stops.</p>
     */
    void markFailed(ClaimedBatch batch, String reason);
}
