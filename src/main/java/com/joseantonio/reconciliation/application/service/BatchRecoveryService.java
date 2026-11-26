package com.joseantonio.reconciliation.application.service;

import com.joseantonio.reconciliation.application.port.in.RecoverBatchesUseCase;
import com.joseantonio.reconciliation.application.port.out.BatchDispatchPort;
import com.joseantonio.reconciliation.application.port.out.BatchRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Failure recovery. Run at startup and periodically:
 * <ol>
 *   <li>PROCESSING batches without heartbeat (crashed node/process) go back to PENDING.</li>
 *   <li>Every PENDING batch is (re)dispatched: it covers restarts, dispatch failures and batches that could not be
 *       claimed earlier because another batch of the same source was running.</li>
 * </ol>
 * Replaying a batch is safe: processing purges whatever a previous attempt left behind.
 */
public final class BatchRecoveryService implements RecoverBatchesUseCase {

    private static final Logger log = LoggerFactory.getLogger(BatchRecoveryService.class);

    private final BatchRepositoryPort batches;
    private final BatchDispatchPort dispatcher;
    private final ProcessingSettings settings;

    public BatchRecoveryService(BatchRepositoryPort batches, BatchDispatchPort dispatcher, ProcessingSettings settings) {
        this.batches = batches;
        this.dispatcher = dispatcher;
        this.settings = settings;
    }

    @Override
    public RecoveryReport recover() {
        List<Long> released = batches.releaseStaleBatches(settings.staleAfter());
        if (!released.isEmpty()) {
            log.warn("Released {} stale PROCESSING batches: {}", released.size(), released);
        }
        int dispatched = 0;
        for (long id : batches.findPendingBatchIds(settings.recoveryLimit())) {
            if (dispatcher.dispatch(id)) {
                dispatched++;
            }
        }
        return new RecoveryReport(released.size(), dispatched);
    }
}
