package com.joseantonio.reconciliation.infrastructure.batch;

import com.joseantonio.reconciliation.application.port.in.ProcessBatchUseCase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncBatchDispatcherTest {

    @Test
    void neverRunsMoreBatchesThanTheConfiguredLimitEvenWithVirtualThreads() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch allDone = new CountDownLatch(20);
        ProcessBatchUseCase slow = id -> {
            int now = running.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                running.decrementAndGet();
                allDone.countDown();
            }
        };
        try (var dispatcher = new AsyncBatchDispatcher(Executors.newVirtualThreadPerTaskExecutor(), 3, slow)) {
            for (long id = 1; id <= 20; id++) {
                assertTrue(dispatcher.dispatch(id));
            }
            assertTrue(allDone.await(10, TimeUnit.SECONDS));
        }
        assertTrue(peak.get() <= 3, "peak concurrency was " + peak.get());
        assertTrue(peak.get() >= 2, "work should actually run in parallel, peak=" + peak.get());
    }

    @Test
    void sameBatchIsNotQueuedTwiceWhileInFlight() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ProcessBatchUseCase blocking = id -> {
            calls.incrementAndGet();
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        try (var dispatcher = new AsyncBatchDispatcher(Executors.newFixedThreadPool(2), 2, blocking)) {
            assertTrue(dispatcher.dispatch(7));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertFalse(dispatcher.dispatch(7), "already in flight on this node");
            assertEquals(1, dispatcher.inFlightCount());
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (dispatcher.inFlightCount() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(0, dispatcher.inFlightCount());
            assertTrue(dispatcher.dispatch(7), "can be dispatched again once finished");
        }
        assertEquals(2, calls.get());
    }

    @Test
    void aFailingBatchNeitherKillsTheDispatcherNorLeaksItsSlot() throws Exception {
        List<Long> processed = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(2);
        ProcessBatchUseCase flaky = id -> {
            try {
                if (id == 1) {
                    throw new IllegalStateException("boom");
                }
                synchronized (processed) {
                    processed.add(id);
                }
            } finally {
                done.countDown();
            }
        };
        try (var dispatcher = new AsyncBatchDispatcher(Executors.newVirtualThreadPerTaskExecutor(), 1, flaky)) {
            dispatcher.dispatch(1);
            dispatcher.dispatch(2);
            assertTrue(done.await(5, TimeUnit.SECONDS), "permit must be released after a failure");
        }
        assertEquals(List.of(2L), processed);
    }

    @Test
    void rejectedExecutionReleasesTheInFlightMark() {
        ExecutorService closed = Executors.newSingleThreadExecutor();
        closed.shutdown();
        var dispatcher = new AsyncBatchDispatcher(closed, 1, id -> { });
        assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> dispatcher.dispatch(1));
        assertEquals(0, dispatcher.inFlightCount());
    }

    @Test
    void invalidConcurrencyIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new AsyncBatchDispatcher(Executors.newSingleThreadExecutor(), 0, id -> { }));
    }
}
