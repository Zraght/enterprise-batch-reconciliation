package com.joseantonio.reconciliation.application;

import com.joseantonio.reconciliation.application.port.out.ClaimedBatch;
import com.joseantonio.reconciliation.application.service.ReconciliationPolicy;
import com.joseantonio.reconciliation.domain.exception.BatchOwnershipLostException;
import com.joseantonio.reconciliation.domain.model.AuditAlert;
import com.joseantonio.reconciliation.domain.model.ReferenceCode;
import com.joseantonio.reconciliation.domain.model.Severity;
import com.joseantonio.reconciliation.domain.exception.ReconciliationConflictException;
import com.joseantonio.reconciliation.domain.model.BatchStatus;
import com.joseantonio.reconciliation.domain.model.MatchStatus;
import com.joseantonio.reconciliation.testsupport.Harness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.joseantonio.reconciliation.testsupport.TestData.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fencing of the purge, replay safety of cross-batch resolutions, and persistence failures. */
class RecoveryAndFailureTest {

    private static final String HEADER = "external_ref,amount,currency,settlement_timestamp\n";
    private static final Instant S1 = Instant.parse("2026-03-10T12:00:00Z");

    @TempDir
    Path dir;
    Harness h;

    @BeforeEach
    void setUp() {
        h = new Harness(dir); // lag 0
    }

    private static String row(String ref, String amount) {
        return ref + "," + amount + ",PEN," + S1 + "\n";
    }

    private long register(String name, String body) {
        return h.upload("NIUBIZ", name, HEADER + body).batch().batchId();
    }

    private long run(String name, String body) {
        long id = register(name, body);
        h.processing.process(id);
        assertEquals(BatchStatus.COMPLETED, h.world.row(id).status, h.world.row(id).failure);
        return id;
    }

    // ------------------------------------------------------------------ fencing of the purge

    @Test
    void aStaleWorkerCannotPurgeWhatTheNewAttemptOwns() {
        String body = row("A", "1.00") + row("B", "2.00") + row("C", "3.00");
        long id = register("a.csv", body);

        ClaimedBatch stale = h.world.tryClaim(id).orElseThrow();
        h.world.purgeDerivedData(stale);
        h.ingestion.ingest(stale);

        h.clock.advance(Duration.ofMinutes(6));
        assertEquals(List.of(id), h.world.releaseStaleBatches(Duration.ofMinutes(5)));
        ClaimedBatch fresh = h.world.tryClaim(id).orElseThrow();
        assertEquals(2, fresh.attempt());
        h.world.purgeDerivedData(fresh);
        h.ingestion.ingest(fresh);
        assertEquals(3, h.world.settlementCount(id));
        int totalBefore = h.world.row(id).total;

        // the stale worker wakes up in the middle of its replay and tries to clean up: it must delete NOTHING
        assertThrows(BatchOwnershipLostException.class, () -> h.world.purgeDerivedData(stale));
        assertEquals(3, h.world.settlementCount(id), "the new attempt's settlements are intact");
        assertEquals(totalBefore, h.world.row(id).total, "and so are its counters");
        assertEquals(BatchStatus.PROCESSING, h.world.row(id).status);
        h.world.markFailed(stale, "stale worker giving up");
        assertEquals(BatchStatus.PROCESSING, h.world.row(id).status, "a stale worker cannot even fail the batch");
        assertNull(h.world.row(id).failure);
    }

    @Test
    void theStaleWorkerIsAlsoFencedOffEveryOtherWrite() {
        long id = register("a.csv", row("A", "1.00"));
        ClaimedBatch stale = h.world.tryClaim(id).orElseThrow();
        h.clock.advance(Duration.ofMinutes(6));
        h.world.releaseStaleBatches(Duration.ofMinutes(5));
        h.world.tryClaim(id).orElseThrow();

        assertThrows(BatchOwnershipLostException.class, () -> h.world.purgeDerivedData(stale));
        assertThrows(BatchOwnershipLostException.class, () -> h.ingestion.ingest(stale));
        assertThrows(BatchOwnershipLostException.class, () -> h.reconciliation.reconcile(stale, 1, S1, S1));
        var alert = new AuditAlert(id, null, ReferenceCode.of("A"), "TEMPORAL_DUPLICATE", Severity.LOW, "d", S1);
        assertThrows(BatchOwnershipLostException.class, () -> h.world.saveAll(stale, List.of(alert)));
        assertThrows(BatchOwnershipLostException.class,
                () -> h.world.updateProgress(stale, com.joseantonio.reconciliation.domain.model.BatchPhase.AUDIT, 90));
        assertThrows(BatchOwnershipLostException.class, () -> h.world.markCompleted(stale));
        assertTrue(h.world.allAlerts().isEmpty());
    }

    // ------------------------------------------------------------------ replay and cross-batch resolutions

    @Test
    void aReplayUndoesTheResolutionsOfThePreviousAttemptAndMakesThemAgain() {
        h.world.saveAll(List.of(entry("OLD", "ACC-0001", "20.00", "PEN", Instant.parse("2026-03-01T10:00:00Z"))));
        long first = run("a.csv", row("OTHER", "1.00"));
        assertEquals(MatchStatus.MISSING_EXTERNAL, h.world.result(first, "OLD").status());

        // batch 2 pairs OLD (resolving batch 1's discrepancy) and then its worker dies before finishing
        long second = register("b.csv", row("OLD", "20.00"));
        ClaimedBatch dead = h.world.tryClaim(second).orElseThrow();
        h.world.purgeDerivedData(dead);
        var ingested = h.ingestion.ingest(dead);
        h.reconciliation.reconcile(dead, ingested.validRecords(), ingested.earliest(), ingested.latest());
        assertEquals(second, h.world.resolvedBy(first, "OLD"));

        h.clock.advance(Duration.ofMinutes(6));
        h.world.releaseStaleBatches(Duration.ofMinutes(5));
        ClaimedBatch replay = h.world.tryClaim(second).orElseThrow();
        h.world.purgeDerivedData(replay);
        assertNull(h.world.resolvedBy(first, "OLD"), "derived data of the dead attempt is reverted, including resolutions");
        assertNull(h.world.reconciledBy("OLD"));

        h.processing.process(second); // already PROCESSING by 'replay' => not claimable; finish it explicitly
        var again = h.ingestion.ingest(replay);
        h.reconciliation.reconcile(replay, again.validRecords(), again.earliest(), again.latest());
        h.audit.audit(replay);
        h.world.markCompleted(replay);
        assertEquals(second, h.world.resolvedBy(first, "OLD"));
        assertEquals(MatchStatus.MATCHED, h.world.result(second, "OLD").status());
        assertEquals(1, h.query.getSummary(first).resolvedByLaterBatches());
    }

    // ------------------------------------------------------------------ persistence failures

    private String manyRows(int n) {
        StringBuilder sb = new StringBuilder();
        List<com.joseantonio.reconciliation.domain.model.LedgerEntry> ledger = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            ledger.add(entry("F-" + i, "ACC-" + (1000 + i), "10.00", "PEN", Instant.parse("2026-03-05T10:00:00Z")));
            sb.append(row("F-" + i, "10.00"));
        }
        h.world.saveAll(ledger);
        return sb.toString();
    }

    @Test
    void aDatabaseFailureInTheMiddleOfReconciliationFailsTheBatchWithoutLeakingDetailsAndAReplayHeals() {
        String body = manyRows(10); // chunk size 3 => several chunks
        AtomicInteger calls = new AtomicInteger();
        h.world.beforeResultChunk = () -> {
            if (calls.incrementAndGet() == 2) {
                throw new IllegalStateException("connection reset by db01.internal password=s3cr3t");
            }
        };
        long id = register("a.csv", body);
        h.processing.process(id);

        assertEquals(BatchStatus.FAILED, h.world.row(id).status);
        String reason = h.world.row(id).failure;
        assertTrue(reason.startsWith("INTERNAL_ERROR"), reason);
        assertFalse(reason.contains("s3cr3t") || reason.contains("db01") || reason.contains("connection reset"), reason);
        assertTrue(h.world.results(id).size() < 10, "the first chunk was committed, the rest was not");

        h.world.beforeResultChunk = () -> { };
        var retry = h.upload("NIUBIZ", "a.csv", HEADER + body);
        assertEquals(com.joseantonio.reconciliation.application.port.in.UploadResult.Outcome.RETRIED, retry.outcome());
        h.processing.process(id);

        assertEquals(BatchStatus.COMPLETED, h.world.row(id).status, h.world.row(id).failure);
        assertEquals(10, h.world.results(id).size());
        assertEquals(10L, h.world.countByStatus(id).get(MatchStatus.MATCHED));
        assertEquals(10, h.world.settlementCount(id), "no duplicated settlements after the replay");
        assertEquals(2, h.world.row(id).attempt);
    }

    @Test
    void aFailureDuringIngestionAndDuringAuditAreHandledTheSameWay() {
        String body = manyRows(7);
        h.world.beforeIngestionChunk = () -> {
            throw new RuntimeException("disk full");
        };
        long id = register("a.csv", body);
        h.processing.process(id);
        assertEquals(BatchStatus.FAILED, h.world.row(id).status);
        assertTrue(h.world.row(id).failure.startsWith("INTERNAL_ERROR"));
        assertEquals(0, h.world.settlementCount(id));

        h.world.beforeIngestionChunk = () -> { };
        h.world.beforeAlertWrite = () -> {
            throw new RuntimeException("deadlock victim");
        };
        h.upload("NIUBIZ", "a.csv", HEADER + body);
        h.processing.process(id);
        // nothing triggers an alert in this data set, so the alert write is never reached: the batch completes
        assertEquals(BatchStatus.COMPLETED, h.world.row(id).status, h.world.row(id).failure);
    }

    @Test
    void aBusinessConflictIsReportedWithItsCodeButNothingElse() {
        String body = manyRows(4);
        h.world.beforeResultChunk = () -> {
            throw new ReconciliationConflictException("ledger entry 17 taken by batch 99 on host db02");
        };
        long id = register("a.csv", body);
        h.processing.process(id);
        assertEquals(BatchStatus.FAILED, h.world.row(id).status);
        assertTrue(h.world.row(id).failure.startsWith("RECONCILIATION_CONFLICT:"), h.world.row(id).failure);
    }

    @Test
    void aFailedBatchLeavesTheLedgerFreeForTheNextAttempt() {
        String body = manyRows(6);
        AtomicInteger calls = new AtomicInteger();
        h.world.beforeResultChunk = () -> {
            if (calls.incrementAndGet() == 2) {
                throw new IllegalStateException("boom");
            }
        };
        long id = register("a.csv", body);
        h.processing.process(id);
        assertEquals(BatchStatus.FAILED, h.world.row(id).status);
        // entries consumed by the committed chunk stay marked until the replay purges them: the replay must not trip over them
        h.world.beforeResultChunk = () -> { };
        h.upload("NIUBIZ", "a.csv", HEADER + body);
        h.processing.process(id);
        assertEquals(BatchStatus.COMPLETED, h.world.row(id).status, h.world.row(id).failure);
        assertEquals(6L, h.world.countByStatus(id).get(MatchStatus.MATCHED));
    }

    @Test
    void aFailedBatchReleasesItsClaimsSoOtherBatchesAreNotBlockedByIt() {
        String body = manyRows(6);
        AtomicInteger calls = new AtomicInteger();
        h.world.beforeResultChunk = () -> {
            if (calls.incrementAndGet() == 2) {
                throw new IllegalStateException("boom");
            }
        };
        long failing = register("a.csv", body);
        h.processing.process(failing);
        assertEquals(BatchStatus.FAILED, h.world.row(failing).status);
        assertNull(h.world.reconciledBy("F-1"), "the first chunk had consumed F-1..F-3: the failure gives them back");
        assertNull(h.world.reconciledBy("F-3"));
        h.world.beforeResultChunk = () -> { };

        // ANOTHER file of the same source can reconcile those very movements, without anybody retrying the failed batch
        long other = run("b.csv", row("F-1", "10.00") + row("F-2", "10.00") + row("F-3", "10.00"));
        assertEquals(MatchStatus.MATCHED, h.world.result(other, "F-1").status());
        assertEquals(MatchStatus.MATCHED, h.world.result(other, "F-3").status());
        assertEquals(other, h.world.reconciledBy("F-1"));
    }

    @Test
    void aFailedBatchReopensTheDiscrepanciesItHadResolvedInEarlierBatches() {
        h.world.saveAll(List.of(entry("OLD", "ACC-0001", "20.00", "PEN", Instant.parse("2026-03-01T10:00:00Z"))));
        long first = run("a.csv", row("OTHER", "1.00"));
        assertEquals(MatchStatus.MISSING_EXTERNAL, h.world.result(first, "OLD").status());

        // batch 2 pairs OLD (resolving batch 1's discrepancy) in its first chunk and then fails
        h.world.beforeResultChunk = new Runnable() {
            int calls;

            @Override
            public void run() {
                if (++calls == 2) {
                    throw new IllegalStateException("boom");
                }
            }
        };
        long second = register("b.csv", row("OLD", "20.00") + row("X1", "1.00") + row("X2", "1.00") + row("X3", "1.00")
                + row("X4", "1.00") + row("X5", "1.00"));
        h.processing.process(second);
        assertEquals(BatchStatus.FAILED, h.world.row(second).status);
        assertNull(h.world.resolvedBy(first, "OLD"), "a FAILED batch cannot claim to have resolved anything");
        assertEquals(2, h.query.getSummary(first).openDiscrepancies(), "OLD (missing external) and OTHER (missing internal)");
        assertEquals(0, h.query.getSummary(first).resolvedByLaterBatches());
    }
}
