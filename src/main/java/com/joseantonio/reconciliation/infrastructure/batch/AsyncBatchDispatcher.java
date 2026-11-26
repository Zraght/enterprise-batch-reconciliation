package com.joseantonio.reconciliation.infrastructure.batch;

import com.joseantonio.reconciliation.application.port.in.ProcessBatchUseCase;
import com.joseantonio.reconciliation.application.port.out.BatchDispatchPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Runs batches asynchronously. Three protections:
 * <ul>
 *   <li>a {@link Semaphore} caps the batches running at once on this node (the real bottleneck is the JDBC pool and
 *       the database, not the thread count, so the cap protects them even with cheap virtual threads);</li>
 *   <li>an in-flight set prevents queuing the same batch twice on this node;</li>
 *   <li>a failure inside one batch never reaches the executor: it is already recorded in the batch row.</li>
 * </ul>
 * Cross-node exclusion does NOT live here: it is enforced by the database claim.
 */
public final class AsyncBatchDispatcher implements BatchDispatchPort, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AsyncBatchDispatcher.class);

    private final ExecutorService executor;
    private final Semaphore permits;
    private final ProcessBatchUseCase processor;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public AsyncBatchDispatcher(ExecutorService executor, int maxConcurrentBatches, ProcessBatchUseCase processor) {
        if (maxConcurrentBatches < 1) {
            throw new IllegalArgumentException("maxConcurrentBatches must be >= 1");
        }
        this.executor = executor;
        this.permits = new Semaphore(maxConcurrentBatches);
        this.processor = processor;
    }

    @Override
    public boolean dispatch(long batchId) {
        if (!inFlight.add(batchId)) {
            return false;
        }
        try {
            executor.execute(() -> run(batchId));
            return true;
        } catch (RejectedExecutionException e) {
            inFlight.remove(batchId);
            throw e;
        }
    }

    private void run(long batchId) {
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            inFlight.remove(batchId);
            return;
        }
        try {
            processor.process(batchId);
        } catch (RuntimeException e) {
            log.error("Unexpected error while processing batch {}", batchId, e);
        } finally {
            permits.release();
            inFlight.remove(batchId);
        }
    }

    public int inFlightCount() {
        return inFlight.size();
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("Batches still running at shutdown; the stale-batch recovery will resume them after restart");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
