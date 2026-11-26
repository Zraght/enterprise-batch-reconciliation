package com.joseantonio.reconciliation.infrastructure.persistence;

import com.joseantonio.reconciliation.application.port.in.QueryBatchUseCase;
import com.joseantonio.reconciliation.application.port.in.RecoverBatchesUseCase;
import com.joseantonio.reconciliation.application.port.in.UploadBatchCommand;
import com.joseantonio.reconciliation.application.port.in.UploadBatchUseCase;
import com.joseantonio.reconciliation.application.port.out.BatchRepositoryPort;
import com.joseantonio.reconciliation.application.port.out.ClaimedBatch;
import com.joseantonio.reconciliation.application.port.out.FileStoragePort;
import com.joseantonio.reconciliation.application.port.out.IngestionChunk;
import com.joseantonio.reconciliation.application.port.out.NewBatch;
import com.joseantonio.reconciliation.application.port.out.SettlementStorePort;
import com.joseantonio.reconciliation.application.port.out.StoredFile;
import com.joseantonio.reconciliation.application.port.in.ProcessBatchUseCase;
import com.joseantonio.reconciliation.domain.exception.BatchOwnershipLostException;
import com.joseantonio.reconciliation.domain.model.BatchPhase;
import com.joseantonio.reconciliation.domain.model.BatchStatus;
import com.joseantonio.reconciliation.domain.model.ExternalSettlement;
import com.joseantonio.reconciliation.domain.model.FileFormat;
import com.joseantonio.reconciliation.domain.model.Money;
import com.joseantonio.reconciliation.domain.model.ReferenceCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Concurrency, recovery and fencing against the real database: these guarantees are enforced by SQL
 * (conditional UPDATEs, a filtered unique index), so they can only be proven against SQL Server.
 * Every test finishes its batches so that recovery runs of other tests never pick up leftovers.
 */
class ConcurrencyAndRecoveryIT extends AbstractSqlServerIT {

    private static final String HEADER = "external_ref,amount,currency,settlement_timestamp\n";
    private static final String TS = "2026-03-10T15:00:00Z";

    @Autowired BatchRepositoryPort batches;
    @Autowired SettlementStorePort settlements;
    @Autowired ProcessBatchUseCase processor;
    @Autowired RecoverBatchesUseCase recovery;
    @Autowired UploadBatchUseCase uploads;
    @Autowired QueryBatchUseCase queries;
    @Autowired FileStoragePort storage;

    /** Registers a PENDING batch WITHOUT dispatching it, so the test controls when it runs. */
    private long register(String source, String csvBody) {
        StoredFile file = storage.store(new ByteArrayInputStream((HEADER + csvBody).getBytes(StandardCharsets.UTF_8)), "f.csv");
        return batches.register(new NewBatch(source, "f.csv", FileFormat.CSV, file.sha256(), file.sizeBytes(),
                file.location(), Instant.now(), null, null)).batch().batchId();
    }

    private static String row(String ref, String amount) {
        return ref + "," + amount + ",PEN," + TS + "\n";
    }

    private void awaitStatus(long id, BatchStatus expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (System.nanoTime() < deadline) {
            if (queries.getStatus(id).status() == expected) {
                return;
            }
            Thread.sleep(100);
        }
        fail("Batch " + id + " is " + queries.getStatus(id).status() + ", expected " + expected);
    }

    @Test
    void exactlyOneOfManyConcurrentWorkersWinsTheClaim() throws Exception {
        long id = register(uniqueSource("CLAIM"), row("X1", "1.00"));
        int workers = 16;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ClaimedBatch>> futures = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            Callable<ClaimedBatch> task = () -> {
                start.await();
                return batches.tryClaim(id).orElse(null);
            };
            futures.add(pool.submit(task));
        }
        start.countDown();
        List<ClaimedBatch> winners = new ArrayList<>();
        for (Future<ClaimedBatch> f : futures) {
            ClaimedBatch c = f.get(30, TimeUnit.SECONDS);
            if (c != null) {
                winners.add(c);
            }
        }
        pool.shutdownNow();

        assertEquals(1, winners.size(), "the claim must be atomic");
        assertEquals(1, winners.get(0).attempt());
        batches.markCompleted(winners.get(0));
    }

    @Test
    void onlyOneBatchPerSourceIsProcessingAndTheNextOneCanStartAfterwards() {
        String source = uniqueSource("SOURCE");
        long first = register(source, row("A1", "1.00"));
        long second = register(source, row("B1", "2.00"));

        ClaimedBatch c1 = batches.tryClaim(first).orElseThrow();
        assertTrue(batches.tryClaim(second).isEmpty(), "same source busy: the unique index must refuse the claim");
        assertEquals(BatchStatus.PENDING, queries.getStatus(second).status());

        batches.markCompleted(c1);
        ClaimedBatch c2 = batches.tryClaim(second).orElseThrow();
        batches.markCompleted(c2);
    }

    @Test
    void aStaleWorkerIsFencedOffAfterItsBatchWasReleasedAndReclaimed() {
        long id = register(uniqueSource("FENCE"), row("F1", "1.00"));
        ClaimedBatch old = batches.tryClaim(id).orElseThrow();

        // the old worker stops heartbeating; the batch is released and claimed again by a new attempt
        jdbc.update("UPDATE reconciliation_batches SET last_heartbeat = DATEADD(MINUTE, -30, SYSUTCDATETIME()) WHERE batch_id = ?", id);
        assertTrue(batches.releaseStaleBatches(java.time.Duration.ofMinutes(5)).contains(id));
        ClaimedBatch fresh = batches.tryClaim(id).orElseThrow();
        assertEquals(2, fresh.attempt());

        // everything the old worker still tries to do is refused
        assertThrows(BatchOwnershipLostException.class, () -> batches.updateProgress(old, BatchPhase.AUDIT, 90));
        assertThrows(BatchOwnershipLostException.class,
                () -> settlements.saveChunk(old, new IngestionChunk(List.of(), List.of(), 0, 10)));
        assertThrows(BatchOwnershipLostException.class, () -> batches.markCompleted(old));

        batches.markCompleted(fresh);
        assertEquals(BatchStatus.COMPLETED, queries.getStatus(id).status());
    }

    @Test
    void aCrashedBatchIsReleasedByRecoveryAndReplayedToCompletion() throws Exception {
        String source = uniqueSource("CRASH");
        insertLedger(source, "C1", "ACC-0001", "10.00", "PEN", "2026-03-10T15:00:00");
        long id = register(source, row("C1", "10.00") + row("C2", "5.00"));

        // a worker claims the batch and dies: PROCESSING with an old heartbeat
        batches.tryClaim(id).orElseThrow();
        jdbc.update("UPDATE reconciliation_batches SET last_heartbeat = DATEADD(MINUTE, -30, SYSUTCDATETIME()) WHERE batch_id = ?", id);

        RecoverBatchesUseCase.RecoveryReport report = recovery.recover();
        assertTrue(report.releasedStale() >= 1);
        awaitStatus(id, BatchStatus.COMPLETED); // recovery re-dispatched it and the replay finished

        assertEquals(2, queries.getStatus(id).totalRecords(), "replay must not double count");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM external_settlements WHERE batch_id = ?", Integer.class, id));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_results WHERE batch_id = ?", Integer.class, id));
        assertEquals(2, jdbc.queryForObject("SELECT attempt_no FROM reconciliation_batches WHERE batch_id = ?", Integer.class, id));
    }

    @Test
    void sameSourceBatchesUploadedTogetherAreNeverDoubleReconciled() throws Exception {
        String source = uniqueSource("TWIN");
        StringBuilder a = new StringBuilder();
        StringBuilder b = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            insertLedger(source, "L" + i, "ACC-" + (1000 + i), "10.00", "PEN", "2026-03-10T15:00:00");
            (i <= 10 ? a : b).append(row("L" + i, "10.00"));
        }
        long first = uploads.upload(new UploadBatchCommand(source, "a.csv",
                new ByteArrayInputStream((HEADER + a).getBytes(StandardCharsets.UTF_8)))).batch().batchId();
        long second = uploads.upload(new UploadBatchCommand(source, "b.csv",
                new ByteArrayInputStream((HEADER + b).getBytes(StandardCharsets.UTF_8)))).batch().batchId();

        // The one that lost the race stays PENDING until recovery re-dispatches it (recovery scheduler is off in tests).
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (System.nanoTime() < deadline
                && !(queries.getStatus(first).status() == BatchStatus.COMPLETED
                && queries.getStatus(second).status() == BatchStatus.COMPLETED)) {
            recovery.recover();
            Thread.sleep(200);
        }
        assertEquals(BatchStatus.COMPLETED, queries.getStatus(first).status());
        assertEquals(BatchStatus.COMPLETED, queries.getStatus(second).status());

        Integer matched = jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_results WHERE batch_id IN (?, ?) "
                + "AND match_status = 'MATCHED'", Integer.class, first, second);
        assertEquals(20, matched);
        Integer reconciled = jdbc.queryForObject("SELECT COUNT(*) FROM internal_ledger WHERE source_name = ? "
                + "AND reconciled_batch_id IN (?, ?)", Integer.class, source, first, second);
        assertEquals(20, reconciled, "every ledger entry is consumed exactly once");
        // Whichever batch ran first reports the entries of the OTHER file as MISSING_EXTERNAL (they were not in its
        // file) without consuming them, which is exactly what let the second batch match them. Nothing else may appear.
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_results WHERE batch_id IN (?, ?) "
                + "AND match_status NOT IN ('MATCHED', 'MISSING_EXTERNAL')", Integer.class, first, second));
    }

    @Test
    void batchesOfDifferentSourcesProcessInParallel() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int s = 0; s < 3; s++) {
            String source = uniqueSource("PAR" + s);
            StringBuilder csv = new StringBuilder(HEADER);
            for (int i = 1; i <= 30; i++) {
                insertLedger(source, "P" + i, "ACC-" + (2000 + i), "1.00", "PEN", "2026-03-10T15:00:00");
                csv.append(row("P" + i, "1.00"));
            }
            ids.add(uploads.upload(new UploadBatchCommand(source, "p.csv",
                    new ByteArrayInputStream(csv.toString().getBytes(StandardCharsets.UTF_8)))).batch().batchId());
        }
        for (long id : ids) {
            awaitStatus(id, BatchStatus.COMPLETED);
            assertEquals(30, jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_results WHERE batch_id = ? "
                    + "AND match_status = 'MATCHED'", Integer.class, id));
        }
    }

    private static IngestionChunk oneSettlement(String reference) {
        return new IngestionChunk(List.of(new ExternalSettlement(null, ReferenceCode.of(reference), Money.of("1.00", "PEN"),
                Instant.parse("2026-03-10T15:00:00Z"), "{}", 1)), List.of(), 1, 5);
    }

    @Test
    void aStaleWorkerCannotPurgeWhatTheNewAttemptOwns() {
        long id = register(uniqueSource("PURGE"), row("P0", "1.00"));
        ClaimedBatch stale = batches.tryClaim(id).orElseThrow();
        settlements.saveChunk(stale, oneSettlement("OLD-ATTEMPT"));

        // the first worker stalls; the batch is released and claimed by a new attempt, which stores its own data
        jdbc.update("UPDATE reconciliation_batches SET last_heartbeat = DATEADD(MINUTE, -30, SYSUTCDATETIME()) WHERE batch_id = ?", id);
        assertTrue(batches.releaseStaleBatches(java.time.Duration.ofMinutes(5)).contains(id));
        ClaimedBatch fresh = batches.tryClaim(id).orElseThrow();
        settlements.saveChunk(fresh, oneSettlement("NEW-ATTEMPT"));
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM external_settlements WHERE batch_id = ?", Integer.class, id);
        assertEquals(2, before);

        // the stale worker wakes up and tries to clean the batch: the ownership check fails and NOTHING is deleted
        assertThrows(BatchOwnershipLostException.class, () -> batches.purgeDerivedData(stale));
        assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM external_settlements WHERE batch_id = ?", Integer.class, id));
        assertEquals(2, jdbc.queryForObject("SELECT attempt_no FROM reconciliation_batches WHERE batch_id = ?", Integer.class, id));

        // the legitimate owner can purge
        batches.purgeDerivedData(fresh);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM external_settlements WHERE batch_id = ?", Integer.class, id));
        batches.markCompleted(fresh);
    }
}
