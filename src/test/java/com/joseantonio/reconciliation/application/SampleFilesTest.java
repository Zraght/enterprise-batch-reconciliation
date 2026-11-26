package com.joseantonio.reconciliation.application;

import com.joseantonio.reconciliation.application.port.in.UploadResult;
import com.joseantonio.reconciliation.application.service.ReconciliationPolicy;
import com.joseantonio.reconciliation.domain.model.SettlementWindow;
import com.joseantonio.reconciliation.domain.model.AccountType;
import com.joseantonio.reconciliation.domain.model.BatchStatus;
import com.joseantonio.reconciliation.domain.model.LedgerEntry;
import com.joseantonio.reconciliation.domain.model.LedgerStatus;
import com.joseantonio.reconciliation.domain.model.MatchStatus;
import com.joseantonio.reconciliation.domain.model.Money;
import com.joseantonio.reconciliation.domain.model.ReferenceCode;
import com.joseantonio.reconciliation.domain.model.Severity;
import com.joseantonio.reconciliation.application.port.in.UploadBatchCommand;
import com.joseantonio.reconciliation.testsupport.Harness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executes the REAL files under samples/ through the whole pipeline and checks the outcome documented in the README.
 * The ledger is parsed from samples/sample_ledger.sql, so the documentation, the SQL and the engine cannot drift apart.
 */
class SampleFilesTest {

    private static final Path SAMPLES = Path.of("samples");
    private static final Pattern TUPLE = Pattern.compile(
            "\\('([^']+)',\\s*'([^']+)',\\s*([0-9.]+),\\s*'([A-Z]{3})',\\s*'([^']+)',\\s*'([A-Z]+)',"
                    + "\\s*'([A-Z]+)',\\s*'([A-Z]+)',\\s*([01])\\)");

    @TempDir
    Path dir;
    Harness h;

    private static List<LedgerEntry> parseLedger(String file) throws IOException {
        List<LedgerEntry> entries = new ArrayList<>();
        Matcher m = TUPLE.matcher(Files.readString(SAMPLES.resolve(file)));
        while (m.find()) {
            entries.add(new LedgerEntry(null, ReferenceCode.of(m.group(1)), m.group(2),
                    Money.of(new BigDecimal(m.group(3)), m.group(4)),
                    LocalDateTime.parse(m.group(5)).toInstant(ZoneOffset.UTC), LedgerStatus.valueOf(m.group(6)),
                    m.group(7), AccountType.valueOf(m.group(8)), m.group(9).equals("1")));
        }
        return entries;
    }

    @BeforeEach
    void loadLedger() throws IOException {
        // the DEFAULT policy of application.yml: settlement lag P1D (T+1)
        h = new Harness(dir, new ReconciliationPolicy(Duration.ofDays(1)));
        List<LedgerEntry> entries = parseLedger("sample_ledger.sql");
        assertEquals(15, entries.size(), "sample_ledger.sql must contain 15 ledger rows");
        h.world.saveAll(entries);
    }

    private long process(String source, String file) throws IOException {
        try (InputStream in = Files.newInputStream(SAMPLES.resolve(file))) {
            UploadResult r = h.registration.upload(new UploadBatchCommand(source, file, in));
            h.processing.process(r.batch().batchId());
            return r.batch().batchId();
        }
    }

    @Test
    void niubizDayOneProducesTheDocumentedResults() throws IOException {
        long id = process("NIUBIZ", "settlements_niubiz_sample.csv");
        assertEquals(BatchStatus.COMPLETED, h.world.row(id).status, h.world.row(id).failure);

        var s = h.query.getSummary(id);
        assertEquals(14, s.totalRecords());
        assertEquals(11, s.validRecords());
        assertEquals(3, s.invalidRecords());
        assertEquals(java.util.Map.of("DUPLICATE_REFERENCE_CONFLICT", 1L, "DUPLICATE_REFERENCE_IDENTICAL", 1L,
                "INVALID_AMOUNT", 1L), s.invalidRecordsByReason());

        // scope: period inferred from the file, due cutoff = period end - 1 day
        assertEquals(SettlementWindow.Source.INFERRED, s.periodSource());
        assertEquals(Instant.parse("2026-03-10T06:31:00Z"), s.periodStart());
        assertEquals(Instant.parse("2026-03-10T16:01:00Z"), s.periodEnd());
        assertEquals(Instant.parse("2026-03-09T16:01:00Z"), s.dueCutoff());
        assertEquals(2, s.deferredLedgerEntries(), "NBZ-0013 and NBZ-0015 are pending settlement");
        assertNull(h.world.result(id, "NBZ-0013"));
        assertNull(h.world.result(id, "NBZ-0015"));

        assertEquals(12, s.evaluatedReferences());
        assertEquals(6, s.matchedTransactions());
        assertEquals(6, s.discrepancyCount());
        assertEquals(new BigDecimal("50.00"), s.reconciliationRatePercent());
        assertEquals(2L, s.discrepanciesByStatus().get(MatchStatus.AMOUNT_MISMATCH));
        assertEquals(3L, s.discrepanciesByStatus().get(MatchStatus.MISSING_INTERNAL));
        assertEquals(1L, s.discrepanciesByStatus().get(MatchStatus.MISSING_EXTERNAL));
        assertEquals(new BigDecimal("865.0000"), s.totalDiscrepancyAmount().get("PEN"));

        assertEquals(new BigDecimal("-5.0000"), h.world.result(id, "NBZ-0003").discrepancyAmount());
        assertTrue(h.world.result(id, "NBZ-0004").detail().startsWith("CURRENCY_MISMATCH"));
        assertEquals(MatchStatus.MISSING_EXTERNAL, h.world.result(id, "NBZ-0005").status());
        assertTrue(h.world.result(id, "NBZ-0010").detail().contains("LEDGER_NOT_POSTED"));
        assertEquals(MatchStatus.MISSING_INTERNAL, h.world.result(id, "NBZ-0011").status());
        assertEquals(MatchStatus.MISSING_INTERNAL, h.world.result(id, "NBZ-0014").status());

        assertEquals(3, s.alertsGenerated());
        assertEquals(2L, s.alertsBySeverity().get(Severity.HIGH));
        assertEquals(1L, s.alertsBySeverity().get(Severity.MEDIUM));
        var rules = h.world.alerts(id).stream().map(a -> a.reference().value() + ":" + a.rule()).sorted().toList();
        assertEquals(List.of("NBZ-0007:TEMPORAL_DUPLICATE", "NBZ-0008:CORPORATE_THRESHOLD",
                "NBZ-0009:UNUSUAL_TRANSACTION_TIME"), rules);
    }

    @Test
    void niubizDayTwoClosesTheOpenDiscrepanciesOfDayOne() throws IOException {
        long day1 = process("NIUBIZ", "settlements_niubiz_sample.csv");
        h.world.saveAll(parseLedger("sample_ledger_late_booking.sql"));
        long day2 = process("NIUBIZ", "settlements_niubiz_sample_day2.csv");
        assertEquals(BatchStatus.COMPLETED, h.world.row(day2).status, h.world.row(day2).failure);

        var s2 = h.query.getSummary(day2);
        assertEquals(4, s2.evaluatedReferences(), "3 settlements + the late booking of NBZ-0014");
        assertEquals(4, s2.matchedTransactions());
        assertEquals(0, s2.discrepancyCount());
        assertEquals(new BigDecimal("100.00"), s2.reconciliationRatePercent());
        assertEquals(0, s2.deferredLedgerEntries());
        assertTrue(h.world.result(day2, "NBZ-0014").detail().startsWith("LATE_BOOKING"));

        var s1 = h.query.getSummary(day1);
        assertEquals(6, s1.discrepancyCount(), "the day-1 report keeps what it found");
        assertEquals(2, s1.resolvedByLaterBatches(), "NBZ-0005 (late settlement) and NBZ-0014 (late booking)");
        assertEquals(4, s1.openDiscrepancies(), "NBZ-0003, NBZ-0004, NBZ-0010 and NBZ-0011 remain open");
        assertEquals(day2, h.world.resolvedBy(day1, "NBZ-0005"));
        assertEquals(day2, h.world.resolvedBy(day1, "NBZ-0014"));
        assertNull(h.world.resolvedBy(day1, "NBZ-0003"));
    }

    @Test
    void izipayJsonSampleProducesTheDocumentedResults() throws IOException {
        long id = process("IZIPAY", "settlements_izipay_sample.json");
        assertEquals(BatchStatus.COMPLETED, h.world.row(id).status, h.world.row(id).failure);
        var s = h.query.getSummary(id);
        assertEquals(3, s.totalRecords());
        assertEquals(4, s.evaluatedReferences());
        assertEquals(2, s.matchedTransactions());
        assertEquals(new BigDecimal("50.00"), s.reconciliationRatePercent());
        assertEquals(1L, s.discrepanciesByStatus().get(MatchStatus.MISSING_INTERNAL));
        assertEquals(1L, s.discrepanciesByStatus().get(MatchStatus.MISSING_EXTERNAL));
        assertEquals(new BigDecimal("65.5000"), s.totalDiscrepancyAmount().get("PEN"));
        assertEquals(0, s.alertsGenerated());
    }
}
