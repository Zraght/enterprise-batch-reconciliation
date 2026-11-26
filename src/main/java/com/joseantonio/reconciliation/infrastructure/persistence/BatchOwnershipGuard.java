package com.joseantonio.reconciliation.infrastructure.persistence;

import com.joseantonio.reconciliation.application.port.out.ClaimedBatch;
import com.joseantonio.reconciliation.domain.exception.BatchOwnershipLostException;
import com.joseantonio.reconciliation.domain.model.BatchPhase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Fencing. Every chunk transaction starts by touching the batch row with a conditional UPDATE
 * ({@code status = PROCESSING AND attempt_no = :attempt}). It does three things at once:
 * proves the worker still owns the lease, refreshes the heartbeat, and takes the row lock for the rest of the chunk
 * transaction. If the batch was released and re-claimed (new attempt) the UPDATE touches 0 rows and the stale worker
 * is stopped before it can write anything.
 */
@Component
public class BatchOwnershipGuard {

    private final JdbcTemplate jdbc;

    public BatchOwnershipGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void heartbeat(ClaimedBatch batch) {
        int updated = jdbc.update(SqlStatements.OWNERSHIP_HEARTBEAT, batch.batchId(), batch.attempt());
        requireOwned(batch, updated);
    }

    public void heartbeat(ClaimedBatch batch, BatchPhase phase, int progressPercent) {
        int updated = jdbc.update(SqlStatements.OWNERSHIP_HEARTBEAT_WITH_PROGRESS, phase.name(), progressPercent, batch.batchId(), batch.attempt());
        requireOwned(batch, updated);
    }

    static void requireOwned(ClaimedBatch batch, int updatedRows) {
        if (updatedRows != 1) {
            throw new BatchOwnershipLostException(batch.batchId(), batch.attempt());
        }
    }
}
