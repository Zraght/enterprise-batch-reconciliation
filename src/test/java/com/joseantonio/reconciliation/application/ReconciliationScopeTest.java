package com.joseantonio.reconciliation.application;

import com.joseantonio.reconciliation.application.port.in.BatchSummary;
import com.joseantonio.reconciliation.application.port.in.DiscrepancyFilter;
import com.joseantonio.reconciliation.application.port.in.PageQuery;
import com.joseantonio.reconciliation.application.port.in.UploadBatchCommand;
import com.joseantonio.reconciliation.application.port.in.UploadResult;
import com.joseantonio.reconciliation.application.service.ReconciliationPolicy;
import com.joseantonio.reconciliation.domain.exception.BatchConflictException;
import com.joseantonio.reconciliation.domain.exception.InvalidRequestException;
import com.joseantonio.reconciliation.domain.model.BatchStatus;
import com.joseantonio.reconciliation.domain.model.LedgerStatus;
import com.joseantonio.reconciliation.domain.model.MatchStatus;
import com.joseantonio.reconciliation.domain.model.SettlementWindow;
import com.joseantonio.reconciliation.testsupport.Harness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static com.joseantonio.reconciliation.testsupport.TestData.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reconciliation SCOPE policy (docs/ARCHITECTURE.md): settlement period, settlement lag, deferred movements,
 * pending/reversed movements, repeated references, and reconciliation across batches. Lag is one day here.
 */
class ReconciliationScopeTest {

    private static final String HEADER = "external_ref,amount,currency,settlement_timestamp\n";
    private static final Instant S1 = Instant.parse("2026-03-10T12:00:00Z");   // the settlement of the first file
    private static final Instant CUTOFF = Instant.parse("2026-03-09T12:00:00Z"); // = S1 - 1 day (period start = end = S1)

    @TempDir
    Path dir;
    Harness h;

    @BeforeEach
    void setUp() {
        h = new Harness(dir, new ReconciliationPolicy(Duration.ofDays(1)));
    }

    private static String row(String ref, String amount, String cur, Instant ts) {
        return ref + "," + amount + "," + cur + "," + ts + "\n";
    }

    private long run(String name, String body) {
        long id = h.upload("NIUBIZ", name, HEADER + body).batch().batchId();
        h.processing.process(id);
        assertEquals(BatchStatus.COMPLETED, h.world.row(id).status, h.world.row(id).failure);
        return id;
    }

    // ------------------------------------------------------------------ due vs deferred

    @Test
    void onlyMovementsThatShouldAlreadyHaveSettledAreReportedAsMissingFromTheFile() {
        h.world.saveAll(java.util.List.of(
                entry("KNOWN", "ACC-0001", "10.00", "PEN", Instant.parse("2026-03-05T10:00:00Z")),
                entry("OLD", "ACC-0002", "20.00", "PEN", Instant.parse("2026-03-08T10:00:00Z")),
                entry("AT-CUTOFF", "ACC-0003", "30.00", "PEN", CUTOFF),
                entry("AFTER-CUTOFF", "ACC-0004", "40.00", "PEN", CUTOFF.plusSeconds(1)),
                entry("RECENT", "ACC-0005", "50.00", "PEN", Instant.parse("2026-03-10T06:00:00Z"))));
        long id = run("a.csv", row("KNOWN", "10.00", "PEN", S1));

        assertEquals(MatchStatus.MATCHED, h.world.result(id, "KNOWN").status());
        assertEquals(MatchStatus.MISSING_EXTERNAL, h.world.result(id, "OLD").status());
        assertEquals(MatchStatus.MISSING_EXTERNAL, h.world.result(id, "AT-CUTOFF").status(), "the cutoff itself is due");
        assertNull(h.world.result(id, "AFTER-CUTOFF"), "within the settlement lag: pending, not a discrepancy");
        assertNull(h.world.result(id, "RECENT"));
        assertNull(h.world.reconciledBy("RECENT"), "deferred movements are not consumed");

        BatchSummary s = h.query.getSummary(id);
        assertEquals(2, s.deferredLedgerEntries());
        assertEquals(CUTOFF, s.dueCutoff());
        assertEquals(SettlementWindow.Source.INFERRED, s.periodSource());
        assertEquals(S1, s.periodEnd());
        assertEquals(2L, s.discrepanciesByStatus().get(MatchStatus.MISSING_EXTERNAL));
    }

    @Test
    void aSettlementIsPairedWithItsMovementEvenWhenThatMovementIsStillWithinTheLag() {
        h.world.saveAll(java.util.List.of(entry("EARLY", "ACC-0001", "10.00", "PEN", Instant.parse("2026-03-10T11:59:00Z"))));
        long id = run("a.csv", row("EARLY", "10.00", "PEN", S1));
        assertEquals(MatchStatus.MATCHED, h.world.result(id, "EARLY").status(), "pairing does not depend on dates");
        assertEquals(id, h.world.reconciledBy("EARLY"));
    }

    @Test
    void zeroLagMeansEverythingBookedUpToTheEndOfThePeriodIsDue() {
        Harness sameDay = new Harness(dir.resolve("zero"), new ReconciliationPolicy(Duration.ZERO));
        sameDay.world.saveAll(java.util.List.of(
                entry("ON-TIME", "ACC-0001", "10.00", "PEN", S1),
                entry("LATER", "ACC-0002", "20.00", "PEN", S1.plusSeconds(1))));
        long id = sameDay.upload("NIUBIZ", "z.csv", HEADER + row("OTHER", "1.00", "PEN", S1)).batch().batchId();
        sameDay.processing.process(id);
        assertEquals(MatchStatus.MISSING_EXTERNAL, sameDay.world.result(id, "ON-TIME").status());
        assertNull(sameDay.world.result(id, "LATER"));
        assertEquals(1, sameDay.query.getSummary(id).deferredLedgerEntries());
    }

    @Test
    void pendingAndReversedMovementsAreNeverReportedAsMissingFromTheFile() {
        Instant old = Instant.parse("2026-03-01T10:00:00Z");
        h.world.saveAll(java.util.List.of(
                entry("PENDING-OLD", "ACC-0001", "10.00", "PEN", old, LedgerStatus.PENDING, "NIUBIZ"),
                entry("REVERSED-OLD", "ACC-0002", "20.00", "PEN", old, LedgerStatus.REVERSED, "NIUBIZ"),
                entry("POSTED-OLD", "ACC-0003", "30.00", "PEN", old)));
        long id = run("a.csv", row("OTHER", "1.00", "PEN", S1));
        assertEquals(MatchStatus.MISSING_EXTERNAL, h.world.result(id, "POSTED-OLD").status());
        assertNull(h.world.result(id, "PENDING-OLD"));
        assertNull(h.world.result(id, "REVERSED-OLD"));
    }

    @Test
    void aSettlementForANonPostedMovementIsMissingInternalAndSaysWhy() {
        Instant old = Instant.parse("2026-03-01T10:00:00Z");
        h.world.saveAll(java.util.List.of(
                entry("P-1", "ACC-0001", "10.00", "PEN", old, LedgerStatus.PENDING, "NIUBIZ"),
                entry("P-2", "ACC-0002", "20.00", "PEN", old, LedgerStatus.REVERSED, "NIUBIZ")));
        long id = run("a.csv", row("P-1", "10.00", "PEN", S1) + row("P-2", "20.00", "PEN", S1));
        for (String ref : new String[]{"P-1", "P-2"}) {
            var r = h.world.result(id, ref);
            assertEquals(MatchStatus.MISSING_INTERNAL, r.status());
            assertTrue(r.detail().contains("LEDGER_NOT_POSTED"), r.detail());
            assertNull(r.ledgerTxId(), "a non-posted movement is not linked (it must not be audited as reconciled)");
        }
        assertTrue(h.world.result(id, "P-1").detail().contains("PENDING"));
        assertTrue(h.world.result(id, "P-2").detail().contains("REVERSED"));
    }

    @Test
    void aReferenceMayBeReusedOnceTheEarlierMovementIsReversedButNeverTwiceAsPosted() {
        Instant t = Instant.parse("2026-03-01T10:00:00Z");
        h.world.saveAll(java.util.List.of(entry("RE-USED", "ACC-0001", "10.00", "PEN", t, LedgerStatus.REVERSED, "NIUBIZ")));
        h.world.saveAll(java.util.List.of(entry("RE-USED", "ACC-0001", "10.00", "PEN", t.plusSeconds(60))));
        assertThrows(IllegalStateException.class,
                () -> h.world.saveAll(java.util.List.of(entry("RE-USED", "ACC-0009", "99.00", "PEN", t.plusSeconds(120)))),
                "at most one POSTED movement per (source, reference)");
        long id = run("a.csv", row("RE-USED", "10.00", "PEN", S1));
        assertEquals(MatchStatus.MATCHED, h.world.result(id, "RE-USED").status(), "the POSTED one is the candidate");
    }

    // ------------------------------------------------------------------ declared settlement period

    private UploadResult uploadWithPeriod(String name, String body, Instant start, Instant end) {
        return h.registration.upload(new UploadBatchCommand("NIUBIZ", name,
                new ByteArrayInputStream((HEADER + body).getBytes(StandardCharsets.UTF_8)), start, end));
    }

    @Test
    void aDeclaredPeriodOverridesTheInferredOneAndMovesTheCutoff() {
        h.world.saveAll(java.util.List.of(entry("RECENT", "ACC-0001", "10.00", "PEN", Instant.parse("2026-03-10T06:00:00Z"))));
        Instant start = Instant.parse("2026-03-01T00:00:00Z");
        Instant end = Instant.parse("2026-03-20T00:00:00Z");
        long id = uploadWithPeriod("a.csv", row("OTHER", "1.00", "PEN", S1), start, end).batch().batchId();
        h.processing.process(id);

        // inferred period would have made RECENT deferred; the declared one covers up to Mar 20 => it is overdue
        assertEquals(MatchStatus.MISSING_EXTERNAL, h.world.result(id, "RECENT").status());
        BatchSummary s = h.query.getSummary(id);
        assertEquals(SettlementWindow.Source.DECLARED, s.periodSource());
        assertEquals(start, s.periodStart());
        assertEquals(end, s.periodEnd());
        assertEquals(end.minus(Duration.ofDays(1)), s.dueCutoff());
    }

    @Test
    void theSameFileWithAnotherPeriodIsAConflictButTheSamePeriodIsAnIdempotentReplay() {
        Instant start = Instant.parse("2026-03-01T00:00:00Z");
        Instant end = Instant.parse("2026-03-02T00:00:00Z");
        String body = row("X", "1.00", "PEN", S1);
        UploadResult first = uploadWithPeriod("a.csv", body, start, end);
        assertEquals(UploadResult.Outcome.EXISTING, uploadWithPeriod("a.csv", body, start, end).outcome());
        assertEquals(UploadResult.Outcome.EXISTING, uploadWithPeriod("a.csv", body, null, null).outcome(),
                "no period supplied on replay: nothing to contradict");
        BatchConflictException ex = assertThrows(BatchConflictException.class,
                () -> uploadWithPeriod("a.csv", body, start, end.plusSeconds(1)));
        assertEquals("BATCH_PERIOD_CONFLICT", ex.code());
        assertNotNull(first.batch());
    }

    @Test
    void aFailedBatchTakesTheNewPeriodWhenTheSameFileIsUploadedAgain() {
        String bad = "foo,bar\n1,2\n";
        Instant a = Instant.parse("2026-03-01T00:00:00Z");
        long id = h.registration.upload(new UploadBatchCommand("NIUBIZ", "bad.csv",
                new ByteArrayInputStream(bad.getBytes(StandardCharsets.UTF_8)), a, a.plusSeconds(60))).batch().batchId();
        h.processing.process(id);
        assertEquals(BatchStatus.FAILED, h.world.row(id).status);

        Instant b = Instant.parse("2026-04-01T00:00:00Z");
        UploadResult retry = h.registration.upload(new UploadBatchCommand("NIUBIZ", "bad.csv",
                new ByteArrayInputStream(bad.getBytes(StandardCharsets.UTF_8)), b, b.plusSeconds(60)));
        assertEquals(UploadResult.Outcome.RETRIED, retry.outcome());
        assertEquals(b, retry.batch().declaredPeriodStart());
    }

    @Test
    void invalidPeriodsAreRejectedBeforeAnythingIsStored() {
        Instant t = Instant.parse("2026-03-01T00:00:00Z");
        assertEquals("INVALID_PERIOD", assertThrows(InvalidRequestException.class,
                () -> uploadWithPeriod("a.csv", row("X", "1", "PEN", S1), t, null)).code());
        assertEquals("INVALID_PERIOD", assertThrows(InvalidRequestException.class,
                () -> uploadWithPeriod("a.csv", row("X", "1", "PEN", S1), null, t)).code());
        assertEquals("INVALID_PERIOD", assertThrows(InvalidRequestException.class,
                () -> uploadWithPeriod("a.csv", row("X", "1", "PEN", S1), t, t.minusSeconds(1))).code());
        assertEquals(0, h.world.findPendingBatchIds(10).size());
    }

    // ------------------------------------------------------------------ across batches

    @Test
    void aDeferredMovementIsSettledByALaterFileWithNoDiscrepancyAtAll() {
        h.world.saveAll(java.util.List.of(
                entry("KNOWN", "ACC-0001", "10.00", "PEN", Instant.parse("2026-03-05T10:00:00Z")),
                entry("RECENT", "ACC-0002", "50.00", "PEN", Instant.parse("2026-03-10T06:00:00Z"))));
        long first = run("a.csv", row("KNOWN", "10.00", "PEN", S1));
        assertNull(h.world.result(first, "RECENT"));

        long second = run("b.csv", row("RECENT", "50.00", "PEN", Instant.parse("2026-03-11T09:00:00Z")));
        assertEquals(MatchStatus.MATCHED, h.world.result(second, "RECENT").status());
        assertEquals(0, h.query.getSummary(first).discrepancyCount());
        assertEquals(0, h.query.getSummary(second).discrepancyCount());
    }

    @Test
    void aLateSettlementClosesTheEarlierMissingExternalDiscrepancy() {
        h.world.saveAll(java.util.List.of(
                entry("KNOWN", "ACC-0001", "10.00", "PEN", Instant.parse("2026-03-05T10:00:00Z")),
                entry("OLD", "ACC-0002", "20.00", "PEN", Instant.parse("2026-03-08T10:00:00Z"))));
        long first = run("a.csv", row("KNOWN", "10.00", "PEN", S1));
        assertEquals(MatchStatus.MISSING_EXTERNAL, h.world.result(first, "OLD").status());
        assertEquals(1, h.query.getSummary(first).openDiscrepancies());

        long second = run("b.csv", row("OLD", "20.00", "PEN", Instant.parse("2026-03-11T09:00:00Z")));
        assertEquals(MatchStatus.MATCHED, h.world.result(second, "OLD").status());
        assertEquals(second, h.world.resolvedBy(first, "OLD"));

        BatchSummary s = h.query.getSummary(first);
        assertEquals(1, s.discrepancyCount(), "the report of the first batch keeps what it found");
        assertEquals(1, s.resolvedByLaterBatches());
        assertEquals(0, s.openDiscrepancies());
        var open = h.query.getDiscrepancies(first, new DiscrepancyFilter(null, null, null, false), new PageQuery(0, 10));
        var closed = h.query.getDiscrepancies(first, new DiscrepancyFilter(null, null, null, true), new PageQuery(0, 10));
        assertEquals(0, open.totalElements());
        assertEquals(1, closed.totalElements());
        assertEquals(second, closed.content().get(0).resolvedByBatchId());
    }

    @Test
    void aLateBookingClosesTheEarlierMissingInternalDiscrepancy() {
        h.world.saveAll(java.util.List.of(entry("KNOWN", "ACC-0001", "10.00", "PEN", Instant.parse("2026-03-05T10:00:00Z"))));
        long first = run("a.csv", row("KNOWN", "10.00", "PEN", S1) + row("LATE-OK", "40.00", "PEN", S1)
                + row("LATE-DIFF", "45.00", "PEN", S1));
        assertEquals(MatchStatus.MISSING_INTERNAL, h.world.result(first, "LATE-OK").status());
        assertEquals(MatchStatus.MISSING_INTERNAL, h.world.result(first, "LATE-DIFF").status());

        // the core posts both movements afterwards (booked a day later, so they are not due either way)
        h.world.saveAll(java.util.List.of(
                entry("LATE-OK", "ACC-0002", "40.00", "PEN", Instant.parse("2026-03-10T13:00:00Z")),
                entry("LATE-DIFF", "ACC-0003", "50.00", "PEN", Instant.parse("2026-03-10T13:00:00Z"))));
        long second = run("b.csv", row("UNRELATED", "1.00", "PEN", Instant.parse("2026-03-12T09:00:00Z")));

        var ok = h.world.result(second, "LATE-OK");
        assertEquals(MatchStatus.MATCHED, ok.status());
        assertTrue(ok.detail().startsWith("LATE_BOOKING"), ok.detail());
        assertTrue(ok.detail().contains("batch " + first));
        var diff = h.world.result(second, "LATE-DIFF");
        assertEquals(MatchStatus.AMOUNT_MISMATCH, diff.status());
        assertEquals(new BigDecimal("-5.0000"), diff.discrepancyAmount());

        assertEquals(second, h.world.resolvedBy(first, "LATE-OK"));
        assertEquals(second, h.world.resolvedBy(first, "LATE-DIFF"));
        assertEquals(second, h.world.reconciledBy("LATE-OK"));
        assertEquals(2, h.query.getSummary(first).resolvedByLaterBatches());
    }

    @Test
    void aSettlementRepeatedInALaterFileIsFlaggedAsAlreadyReconciledNotAsAnUnknownOne() {
        h.world.saveAll(java.util.List.of(entry("R", "ACC-0001", "10.00", "PEN", Instant.parse("2026-03-05T10:00:00Z"))));
        long first = run("a.csv", row("R", "10.00", "PEN", S1));
        long second = run("b.csv", row("R", "10.00", "PEN", Instant.parse("2026-03-11T09:00:00Z"))
                + row("OTHER", "2.00", "PEN", Instant.parse("2026-03-11T09:00:00Z")));

        var repeat = h.world.result(second, "R");
        assertEquals(MatchStatus.MISSING_INTERNAL, repeat.status());
        assertTrue(repeat.detail().contains("ALREADY_RECONCILED"), repeat.detail());
        assertTrue(repeat.detail().contains("batch " + first), repeat.detail());
        assertNull(repeat.ledgerTxId());
        assertEquals(first, h.world.reconciledBy("R"), "the movement stays reconciled by the first batch");
    }

    @Test
    void aMissingExternalEntryIsReportedAgainWhileItRemainsUnsettled() {
        h.world.saveAll(java.util.List.of(entry("OLD", "ACC-0001", "20.00", "PEN", Instant.parse("2026-03-01T10:00:00Z"))));
        long first = run("a.csv", row("A", "1.00", "PEN", S1));
        long second = run("b.csv", row("B", "1.00", "PEN", Instant.parse("2026-03-11T09:00:00Z")));
        assertEquals(MatchStatus.MISSING_EXTERNAL, h.world.result(first, "OLD").status());
        assertEquals(MatchStatus.MISSING_EXTERNAL, h.world.result(second, "OLD").status(),
                "still overdue and still unsettled: it stays visible until it is paired");
        assertNull(h.world.resolvedBy(first, "OLD"));
    }

    // ------------------------------------------------------------------ repeated references and currencies

    @Test
    void repeatedReferencesInAFileAreClassifiedAsIdenticalReplaysOrConflicts() {
        h.world.saveAll(java.util.List.of(entry("D1", "ACC-0001", "100.00", "PEN", Instant.parse("2026-03-05T10:00:00Z"))));
        StringBuilder body = new StringBuilder();
        body.append(row("D1", "100.00", "PEN", S1));
        body.append(row("D1", "100.00", "PEN", S1));          // identical replay
        body.append(row("D1", "100.01", "PEN", S1));          // conflicting amount
        body.append(row("D1", "100.00", "USD", S1));          // conflicting currency
        for (int i = 1; i <= 8; i++) {
            body.append(row("V" + i, "1.00", "PEN", S1));
        }
        long id = run("a.csv", body.toString());

        assertEquals(MatchStatus.MATCHED, h.world.result(id, "D1").status(), "the first occurrence is the one reconciled");
        BatchSummary s = h.query.getSummary(id);
        assertEquals(3, s.invalidRecords());
        assertEquals(1L, s.invalidRecordsByReason().get("DUPLICATE_REFERENCE_IDENTICAL"));
        assertEquals(2L, s.invalidRecordsByReason().get("DUPLICATE_REFERENCE_CONFLICT"));
    }

    @Test
    void differentCurrenciesAreAMismatchWithoutANumericDifferenceAndAreNeverAddedToTotals() {
        h.world.saveAll(java.util.List.of(
                entry("C-1", "ACC-0001", "100.00", "USD", Instant.parse("2026-03-05T10:00:00Z")),
                entry("C-2", "ACC-0002", "100.00", "PEN", Instant.parse("2026-03-05T10:00:00Z"))));
        long id = run("a.csv", row("C-1", "100.00", "PEN", S1) + row("C-2", "90.00", "PEN", S1));
        var currency = h.world.result(id, "C-1");
        assertEquals(MatchStatus.AMOUNT_MISMATCH, currency.status());
        assertNull(currency.discrepancyAmount());
        assertEquals(1, h.query.getSummary(id).totalDiscrepancyAmount().size());
        assertEquals(new BigDecimal("10.0000"), h.query.getSummary(id).totalDiscrepancyAmount().get("PEN"));
    }
}
