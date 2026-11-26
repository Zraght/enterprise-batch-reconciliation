package com.joseantonio.reconciliation.application;

import com.joseantonio.reconciliation.application.port.in.DiscrepancyFilter;
import com.joseantonio.reconciliation.application.port.in.PageQuery;
import com.joseantonio.reconciliation.application.port.in.UploadResult;
import com.joseantonio.reconciliation.application.port.out.ClaimedBatch;
import com.joseantonio.reconciliation.domain.exception.BatchNotCompletedException;
import com.joseantonio.reconciliation.domain.exception.BatchNotFoundException;
import com.joseantonio.reconciliation.domain.exception.BatchOwnershipLostException;
import com.joseantonio.reconciliation.domain.exception.InvalidRequestException;
import com.joseantonio.reconciliation.domain.model.AccountType;
import com.joseantonio.reconciliation.domain.model.AuditAlert;
import com.joseantonio.reconciliation.domain.model.BatchStatus;
import com.joseantonio.reconciliation.domain.model.MatchStatus;
import com.joseantonio.reconciliation.domain.model.Severity;
import com.joseantonio.reconciliation.testsupport.Harness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.joseantonio.reconciliation.testsupport.TestData.T0;
import static com.joseantonio.reconciliation.testsupport.TestData.ledger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchPipelineTest {

    private static final String HEADER = "external_ref,amount,currency,settlement_timestamp\n";
    private static final String TS = "2026-03-10T15:00:00Z";

    @TempDir
    Path dir;
    Harness h;

    @BeforeEach
    void setUp() {
        h = new Harness(dir);
    }

    private static String row(String ref, String amount, String cur) {
        return ref + "," + amount + "," + cur + "," + TS + "\n";
    }

    private void seedReferenceLedger() {
        h.ledger(
                ledger(0, "REF-001", "ACC-0001", "100.00", "PEN", T0),
                ledger(0, "REF-002", "ACC-0002", "100.00", "PEN", T0),
                ledger(0, "REF-003", "ACC-0003", "50.00", "USD", T0),
                ledger(0, "REF-004", "ACC-0004", "75.00", "PEN", T0),
                // same account/amount 10 s after REF-001 -> temporal duplicate alert
                ledger(0, "REF-008", "ACC-0001", "100.00", "PEN", T0.plusSeconds(10)),
                // 30 000 > RETAIL limit 10 000, not pre-authorised -> 3x => HIGH
                ledger(0, "REF-009", "ACC-0009", "30000.00", "PEN", T0));
    }

    private String referenceFile() {
        return HEADER
                + row("REF-001", "100.00", "PEN")                  // MATCHED
                + row("REF-002", "99.50", "PEN")                   // AMOUNT_MISMATCH -0.50
                + row("REF-003", "50.00", "PEN")                   // CURRENCY mismatch
                + row("REF-005", "10.00", "PEN")                   // MISSING_INTERNAL
                + row("REF-008", "100.00", "PEN")                  // MATCHED
                + row("REF-009", "30000.00", "PEN")                // MATCHED
                + row("REF-006", "abc", "PEN")                     // invalid amount
                + row("REF-001", "1.00", "PEN");                   // repeated reference -> rejected
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void fullPipelineProducesAllFourStatusesAuditAlertsAndTraceability() {
        seedReferenceLedger();
        long id = h.uploadAndProcess("NIUBIZ", "settlements.csv", referenceFile());

        var row = h.world.row(id);
        assertEquals(BatchStatus.COMPLETED, row.status, row.failure);
        assertEquals(100, row.progress);
        assertEquals(8, row.total);
        assertEquals(6, row.valid);
        assertEquals(2, row.invalid);

        assertEquals(MatchStatus.MATCHED, h.world.result(id, "REF-001").status());
        assertEquals(MatchStatus.AMOUNT_MISMATCH, h.world.result(id, "REF-002").status());
        assertEquals(new BigDecimal("-0.5000"), h.world.result(id, "REF-002").discrepancyAmount());
        assertTrue(h.world.result(id, "REF-003").detail().startsWith("CURRENCY_MISMATCH"));
        assertEquals(MatchStatus.MISSING_EXTERNAL, h.world.result(id, "REF-004").status());
        assertEquals(MatchStatus.MISSING_INTERNAL, h.world.result(id, "REF-005").status());
        assertEquals(7, h.world.results(id).size());

        // traceability: results point to the rows they came from
        assertNotNull(h.world.result(id, "REF-001").ledgerTxId());
        assertNotNull(h.world.result(id, "REF-001").settlementId());
        assertNull(h.world.result(id, "REF-004").settlementId());
        assertNull(h.world.result(id, "REF-005").ledgerTxId());

        // paired ledger entries are consumed by this batch, the orphan is not
        assertEquals(id, h.world.reconciledBy("REF-001"));
        assertNull(h.world.reconciledBy("REF-004"));

        // invalid records are kept with reason codes
        var codes = h.world.invalidRecords(id).stream().map(r -> r.reasonCode()).collect(Collectors.toSet());
        assertEquals(java.util.Set.of("INVALID_AMOUNT", "DUPLICATE_REFERENCE_CONFLICT"), codes);

        // audit
        List<AuditAlert> alerts = h.world.alerts(id);
        assertTrue(alerts.stream().anyMatch(a -> a.reference().value().equals("REF-008") && a.rule().equals("TEMPORAL_DUPLICATE")));
        assertTrue(alerts.stream().anyMatch(a -> a.reference().value().equals("REF-009")
                && a.rule().equals("CORPORATE_THRESHOLD") && a.severity() == Severity.HIGH));
        assertEquals(2, alerts.size());
    }

    @Test
    void summaryAndDiscrepancyQueries() {
        seedReferenceLedger();
        long id = h.uploadAndProcess("NIUBIZ", "settlements.csv", referenceFile());

        var summary = h.query.getSummary(id);
        assertEquals(7, summary.evaluatedReferences());
        assertEquals(3, summary.matchedTransactions());
        assertEquals(4, summary.discrepancyCount());
        assertEquals(new BigDecimal("42.86"), summary.reconciliationRatePercent());
        assertEquals(1L, summary.discrepanciesByStatus().get(MatchStatus.MISSING_EXTERNAL));
        assertEquals(2L, summary.discrepanciesByStatus().get(MatchStatus.AMOUNT_MISMATCH));
        assertEquals(1L, summary.discrepanciesByStatus().get(MatchStatus.MISSING_INTERNAL));
        assertEquals(2, summary.alertsGenerated());
        // |-0.50| (PEN) + 75.00 (PEN, missing external) + 10.00 (PEN, missing internal); the currency-mismatch has no amount
        assertEquals(new BigDecimal("85.5000"), summary.totalDiscrepancyAmount().get("PEN"));
        assertFalse(summary.totalDiscrepancyAmount().containsKey("USD"));
        assertEquals(8, summary.totalRecords());
        assertEquals(2, summary.invalidRecords());

        var all = h.query.getDiscrepancies(id, DiscrepancyFilter.none(), new PageQuery(0, 10));
        assertEquals(4, all.totalElements());
        var onlyMissing = h.query.getDiscrepancies(id, new DiscrepancyFilter(MatchStatus.MISSING_EXTERNAL, null, null), new PageQuery(0, 10));
        assertEquals(1, onlyMissing.totalElements());
        assertEquals("REF-004", onlyMissing.content().get(0).referenceCode());
        var byRef = h.query.getDiscrepancies(id, new DiscrepancyFilter(null, "ref-00", "PEN"), new PageQuery(0, 2));
        assertEquals(2, byRef.content().size());
        assertEquals(2, byRef.totalPages());
        assertThrows(InvalidRequestException.class, () -> new DiscrepancyFilter(MatchStatus.MATCHED, null, null));
        assertThrows(InvalidRequestException.class, () -> new PageQuery(0, 0));
    }

    @Test
    void resultsAreOnlyAvailableForCompletedBatches() {
        long id = h.upload("NIUBIZ", "a.csv", HEADER + row("X-1", "1", "PEN")).batch().batchId();
        assertThrows(BatchNotCompletedException.class, () -> h.query.getSummary(id));
        assertThrows(BatchNotCompletedException.class,
                () -> h.query.getDiscrepancies(id, DiscrepancyFilter.none(), new PageQuery(0, 5)));
        assertThrows(BatchNotFoundException.class, () -> h.query.getStatus(9999));
    }

    @Test
    void jsonFilesAreSupportedAndNumbersKeepTheirExactText() {
        h.ledger(ledger(0, "J-1", "ACC-0001", "100.10", "PEN", T0));
        String json = """
                [ {"external_ref":"j-1","amount":100.10,"currency":"PEN","settlement_timestamp":"%s"},
                  {"external_ref":"J-2","amount":"7.5","currency":"usd","settlement_timestamp":"%s","extra":{"a":1}} ]
                """.formatted(TS, TS);
        long id = h.uploadAndProcess("NIUBIZ", "s.json", json);
        assertEquals(BatchStatus.COMPLETED, h.world.row(id).status, h.world.row(id).failure);
        assertEquals(MatchStatus.MATCHED, h.world.result(id, "J-1").status());
        assertEquals(MatchStatus.MISSING_INTERNAL, h.world.result(id, "J-2").status());
    }

    @Test
    void chunkingDoesNotChangeTheOutcome() {
        // chunk size is 3 in the harness; 10 settlements => several chunks in every phase
        List<com.joseantonio.reconciliation.domain.model.LedgerEntry> entries = new ArrayList<>();
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= 10; i++) {
            entries.add(ledger(0, "C-" + i, "ACC-" + (1000 + i), "10.00", "PEN", T0));
            csv.append(row("C-" + i, i % 2 == 0 ? "10.00" : "10.01", "PEN"));
        }
        entries.add(ledger(0, "C-ORPHAN", "ACC-5000", "5.00", "PEN", T0));
        h.world.saveAll(entries);
        long id = h.uploadAndProcess("NIUBIZ", "c.csv", csv.toString());
        var counts = h.world.countByStatus(id);
        assertEquals(5L, counts.get(MatchStatus.MATCHED));
        assertEquals(5L, counts.get(MatchStatus.AMOUNT_MISMATCH));
        assertEquals(1L, counts.get(MatchStatus.MISSING_EXTERNAL));
    }

    // ------------------------------------------------------------------ upload / idempotency

    @Test
    void uploadingTheSameFileTwiceIsIdempotent() {
        UploadResult first = h.upload("NIUBIZ", "a.csv", HEADER + row("X-1", "1", "PEN"));
        UploadResult second = h.upload("NIUBIZ", "renamed.csv", HEADER + row("X-1", "1", "PEN"));
        assertEquals(UploadResult.Outcome.CREATED, first.outcome());
        assertEquals(UploadResult.Outcome.EXISTING, second.outcome());
        assertEquals(first.batch().batchId(), second.batch().batchId());
        assertEquals(1, h.world.dispatched.size(), "an existing batch must not be dispatched again");
        // different source, same content => independent batch
        assertEquals(UploadResult.Outcome.CREATED, h.upload("IZIPAY", "a.csv", HEADER + row("X-1", "1", "PEN")).outcome());
    }

    @Test
    void rejectedUploadsLeaveNoFilesBehind() throws Exception {
        assertThrows(InvalidRequestException.class, () -> h.upload("NIUBIZ", "empty.csv", ""));
        assertThrows(InvalidRequestException.class, () -> h.upload("NIUBIZ", "data.xml", "<a/>"));
        assertThrows(InvalidRequestException.class, () -> h.upload("  ", "a.csv", "x"));
        try (var files = Files.list(dir)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void failedBatchIsRequeuedWhenTheSameFileIsUploadedAgain() {
        String bad = "wrong_header,x\n1,2\n";
        long id = h.uploadAndProcess("NIUBIZ", "bad.csv", bad);
        assertEquals(BatchStatus.FAILED, h.world.row(id).status);
        assertTrue(h.world.row(id).failure.startsWith("MISSING_REQUIRED_COLUMNS"), h.world.row(id).failure);

        UploadResult again = h.upload("NIUBIZ", "bad.csv", bad);
        assertEquals(UploadResult.Outcome.RETRIED, again.outcome());
        assertEquals(id, again.batch().batchId());
        assertEquals(BatchStatus.PENDING, again.batch().status());
    }

    // ------------------------------------------------------------------ file level failures

    @Test
    void structuralFileProblemsFailTheBatchWithAClearReason() {
        assertFailure("empty-body.csv", HEADER, "EMPTY_FILE");
        assertFailure("garbage.csv", HEADER + "a,b,c,d\n" + "e,f,g,h\n", "NO_VALID_RECORDS");
        assertFailure("broken.json", "[ {\"external_ref\": ", "INVALID_JSON");
        assertFailure("object.json", "{\"records\": []}", "INVALID_JSON_STRUCTURE");
    }

    @Test
    void tooManyInvalidRecordsRejectTheFile() {
        StringBuilder csv = new StringBuilder(HEADER).append(row("OK-1", "1", "PEN"));
        for (int i = 0; i < 3; i++) {
            csv.append(row("BAD-" + i, "oops", "PEN"));
        }
        assertFailure("mostly-bad.csv", csv.toString(), "TOO_MANY_INVALID_RECORDS");
    }

    private void assertFailure(String name, String content, String code) {
        long id = h.uploadAndProcess("SRC-" + name, name, content);
        assertEquals(BatchStatus.FAILED, h.world.row(id).status);
        assertTrue(h.world.row(id).failure.startsWith(code + ":"), h.world.row(id).failure);
    }

    @Test
    void rowsWithWrongColumnCountBecomeInvalidRecordsNotFileFailures() {
        h.ledger(ledger(0, "OK-1", "ACC-0001", "1.00", "PEN", T0));
        long id = h.uploadAndProcess("NIUBIZ", "ragged.csv",
                HEADER + row("OK-1", "1.00", "PEN") + "SHORT,5\n" + row("OK-2", "2", "PEN"));
        assertEquals(BatchStatus.COMPLETED, h.world.row(id).status, h.world.row(id).failure);
        assertEquals("MALFORMED_ROW", h.world.invalidRecords(id).get(0).reasonCode());
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    void concurrentWorkersProcessingTheSameBatchProduceExactlyOneConsistentRun() throws Exception {
        seedReferenceLedger();
        long id = h.upload("NIUBIZ", "settlements.csv", referenceFile()).batch().batchId();

        int workers = 12;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                h.processing.process(id);
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertEquals(BatchStatus.COMPLETED, h.world.row(id).status, h.world.row(id).failure);
        assertEquals(1, h.world.row(id).attempt, "exactly one worker may win the claim");
        assertEquals(7, h.world.results(id).size());
        assertEquals(6, h.world.settlementCount(id));
    }

    @Test
    void onlyOneBatchPerSourceRunsAtATimeAndTheOtherIsPickedUpLater() {
        h.ledger(ledger(0, "A-1", "ACC-0001", "1.00", "PEN", T0), ledger(0, "B-1", "ACC-0002", "2.00", "PEN", T0));
        long first = h.upload("NIUBIZ", "1.csv", HEADER + row("A-1", "1.00", "PEN")).batch().batchId();
        long second = h.upload("NIUBIZ", "2.csv", HEADER + row("B-1", "2.00", "PEN")).batch().batchId();

        ClaimedBatch holding = h.world.tryClaim(first).orElseThrow(); // first is "running"
        h.processing.process(second);
        assertEquals(BatchStatus.PENDING, h.world.row(second).status, "same source busy: must stay queued");

        h.world.markCompleted(holding);
        var report = h.recovery.recover();
        assertTrue(report.dispatched() >= 1);
        h.processing.process(second);
        assertEquals(BatchStatus.COMPLETED, h.world.row(second).status, h.world.row(second).failure);
    }

    @Test
    void differentSourcesCanRunConcurrently() {
        long a = h.upload("NIUBIZ", "1.csv", HEADER + row("A-1", "1.00", "PEN")).batch().batchId();
        long b = h.upload("IZIPAY", "2.csv", HEADER + row("B-1", "2.00", "PEN")).batch().batchId();
        assertTrue(h.world.tryClaim(a).isPresent());
        assertTrue(h.world.tryClaim(b).isPresent());
    }

    @Test
    void aLedgerEntryIsNeverReconciledTwiceAcrossBatches() {
        h.ledger(ledger(0, "R-1", "ACC-0001", "10.00", "PEN", T0), ledger(0, "R-2", "ACC-0002", "20.00", "PEN", T0));
        long first = h.uploadAndProcess("NIUBIZ", "1.csv", HEADER + row("R-1", "10.00", "PEN"));
        // R-2 had no settlement in batch 1 -> MISSING_EXTERNAL and still available
        assertEquals(MatchStatus.MISSING_EXTERNAL, h.world.result(first, "R-2").status());

        long second = h.uploadAndProcess("NIUBIZ", "2.csv", HEADER + row("R-1", "10.00", "PEN") + row("R-2", "20.00", "PEN"));
        assertEquals(MatchStatus.MISSING_INTERNAL, h.world.result(second, "R-1").status(),
                "R-1 was consumed by batch 1, so batch 2 cannot match it again");
        assertEquals(MatchStatus.MATCHED, h.world.result(second, "R-2").status(),
                "the orphan of batch 1 is settled by the later file");
    }

    // ------------------------------------------------------------------ recovery & fencing

    @Test
    void crashedBatchIsReleasedReplayedAndEndsConsistent() {
        seedReferenceLedger();
        long id = h.upload("NIUBIZ", "settlements.csv", referenceFile()).batch().batchId();

        // a worker claims the batch and dies half-way through ingestion
        ClaimedBatch dead = h.world.tryClaim(id).orElseThrow();
        h.world.purgeDerivedData(dead);
        h.ingestion.ingest(dead);
        assertEquals(6, h.world.settlementCount(id));
        assertEquals(BatchStatus.PROCESSING, h.world.row(id).status);

        h.clock.advance(Duration.ofMinutes(6)); // no heartbeat for longer than staleAfter
        var report = h.recovery.recover();
        assertEquals(1, report.releasedStale());
        assertEquals(BatchStatus.PENDING, h.world.row(id).status);

        h.processing.process(id);
        assertEquals(BatchStatus.COMPLETED, h.world.row(id).status, h.world.row(id).failure);
        assertEquals(2, h.world.row(id).attempt);
        assertEquals(6, h.world.settlementCount(id), "replay must not duplicate settlements");
        assertEquals(7, h.world.results(id).size());
        assertEquals(8, h.world.row(id).total);

        // the dead worker wakes up: every write is fenced
        assertThrows(BatchOwnershipLostException.class, () -> h.world.updateProgress(dead,
                com.joseantonio.reconciliation.domain.model.BatchPhase.AUDIT, 90));
        assertThrows(BatchOwnershipLostException.class, () -> h.ingestion.ingest(dead));
    }

    @Test
    void recentlyActiveBatchesAreNotReleased() {
        long id = h.upload("NIUBIZ", "a.csv", HEADER + row("X-1", "1", "PEN")).batch().batchId();
        h.world.tryClaim(id).orElseThrow();
        h.clock.advance(Duration.ofMinutes(1));
        assertEquals(0, h.recovery.recover().releasedStale());
        assertEquals(BatchStatus.PROCESSING, h.world.row(id).status);
    }

    @Test
    void auditAlertsAreNotDuplicatedWhenTheSameFindingIsStoredTwice() {
        long id = h.upload("NIUBIZ", "a.csv", HEADER + row("X-1", "1", "PEN")).batch().batchId();
        ClaimedBatch claim = h.world.tryClaim(id).orElseThrow();
        var alert = new AuditAlert(id, null, com.joseantonio.reconciliation.domain.model.ReferenceCode.of("X-1"),
                "TEMPORAL_DUPLICATE", Severity.HIGH, "d", T0);
        h.world.saveAll(claim, List.of(alert));
        h.world.saveAll(claim, List.of(alert));
        assertEquals(1, h.world.alerts(id).size());
    }
}
