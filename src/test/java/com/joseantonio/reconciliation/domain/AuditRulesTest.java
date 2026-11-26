package com.joseantonio.reconciliation.domain;

import com.joseantonio.reconciliation.domain.model.AccountActivity;
import com.joseantonio.reconciliation.domain.model.AccountType;
import com.joseantonio.reconciliation.domain.model.AuditFinding;
import com.joseantonio.reconciliation.domain.model.Severity;
import com.joseantonio.reconciliation.domain.rule.AuditEngine;
import com.joseantonio.reconciliation.domain.rule.CorporateThresholdRule;
import com.joseantonio.reconciliation.domain.rule.OperatingHoursPolicy;
import com.joseantonio.reconciliation.domain.rule.TemporalDuplicateRule;
import com.joseantonio.reconciliation.domain.rule.ThresholdPolicy;
import com.joseantonio.reconciliation.domain.rule.UnusualTransactionTimeRule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.Map;

import static com.joseantonio.reconciliation.testsupport.TestData.T0;
import static com.joseantonio.reconciliation.testsupport.TestData.ledger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditRulesTest {

    private static final String ACC = "ACC-0001";
    private static final Currency PEN = Currency.getInstance("PEN");
    private static final Currency USD = Currency.getInstance("USD");

    // ---------- Temporal duplicates ----------

    private final TemporalDuplicateRule duplicates = new TemporalDuplicateRule(Duration.ofSeconds(30));

    @Test
    void flagsLaterTransactionOfDuplicatePairOnly() {
        var activity = new AccountActivity(ACC, List.of(
                ledger(1, "A1", ACC, "100", "PEN", T0),
                ledger(2, "A2", ACC, "100.00", "PEN", T0.plusSeconds(10))));
        List<AuditFinding> f = duplicates.evaluate(activity);
        assertEquals(1, f.size());
        assertEquals("A2", f.get(0).reference().value());
        assertEquals(Severity.HIGH, f.get(0).severity());
        assertTrue(f.get(0).description().contains("A1"));
    }

    @Test
    void windowIsStrictlyLessThanThirtySeconds() {
        var at29 = new AccountActivity(ACC, List.of(
                ledger(1, "A1", ACC, "5", "PEN", T0), ledger(2, "A2", ACC, "5", "PEN", T0.plusSeconds(29))));
        var at30 = new AccountActivity(ACC, List.of(
                ledger(1, "A1", ACC, "5", "PEN", T0), ledger(2, "A2", ACC, "5", "PEN", T0.plusSeconds(30))));
        assertEquals(1, duplicates.evaluate(at29).size());
        assertEquals(0, duplicates.evaluate(at30).size());
    }

    @Test
    void differentAmountOrCurrencyIsNotADuplicate() {
        var activity = new AccountActivity(ACC, List.of(
                ledger(1, "A1", ACC, "100", "PEN", T0),
                ledger(2, "A2", ACC, "100.01", "PEN", T0.plusSeconds(1)),
                ledger(3, "A3", ACC, "100", "USD", T0.plusSeconds(2))));
        assertTrue(duplicates.evaluate(activity).isEmpty());
    }

    @Test
    void burstOfThreeFlagsSecondAndThird() {
        var activity = new AccountActivity(ACC, List.of(
                ledger(1, "A1", ACC, "9", "PEN", T0),
                ledger(2, "A2", ACC, "9", "PEN", T0.plusSeconds(5)),
                ledger(3, "A3", ACC, "9", "PEN", T0.plusSeconds(10))));
        assertEquals(List.of("A2", "A3"),
                duplicates.evaluate(activity).stream().map(x -> x.reference().value()).toList());
    }

    @Test
    void unsortedInputIsOrderedChronologically() {
        var activity = new AccountActivity(ACC, List.of(
                ledger(2, "A2", ACC, "9", "PEN", T0.plusSeconds(5)),
                ledger(1, "A1", ACC, "9", "PEN", T0)));
        assertEquals("A2", duplicates.evaluate(activity).get(0).reference().value());
    }

    @Test
    void windowMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new TemporalDuplicateRule(Duration.ZERO));
    }

    @Test
    void activityRejectsEntriesOfAnotherAccount() {
        assertThrows(IllegalArgumentException.class,
                () -> new AccountActivity(ACC, List.of(ledger(1, "A1", "ACC-9999", "1", "PEN", T0))));
    }

    // ---------- Corporate threshold ----------

    private final CorporateThresholdRule threshold = new CorporateThresholdRule(new ThresholdPolicy(Map.of(
            AccountType.RETAIL, Map.of(PEN, new BigDecimal("10000")),
            AccountType.CORPORATE, Map.of(PEN, new BigDecimal("500000")))));

    private AccountActivity one(String amount, String cur, AccountType type, boolean preAuth) {
        return new AccountActivity(ACC, List.of(ledger(1, "T1", ACC, amount, cur, T0, type, preAuth)));
    }

    @Test
    void amountEqualToLimitIsNotFlagged() {
        assertTrue(threshold.evaluate(one("10000", "PEN", AccountType.RETAIL, false)).isEmpty());
    }

    @Test
    void severityGrowsWithExcess() {
        assertEquals(Severity.MEDIUM, threshold.evaluate(one("10000.0001", "PEN", AccountType.RETAIL, false)).get(0).severity());
        assertEquals(Severity.HIGH, threshold.evaluate(one("20000", "PEN", AccountType.RETAIL, false)).get(0).severity());
        assertEquals(Severity.CRITICAL, threshold.evaluate(one("50000", "PEN", AccountType.RETAIL, false)).get(0).severity());
    }

    @Test
    void preAuthorizedOperationsAreIgnored() {
        assertTrue(threshold.evaluate(one("900000", "PEN", AccountType.RETAIL, true)).isEmpty());
    }

    @Test
    void noConfiguredLimitMeansNoAlert() {
        assertTrue(threshold.evaluate(one("999999", "USD", AccountType.RETAIL, false)).isEmpty());
        assertTrue(threshold.evaluate(one("999999", "PEN", AccountType.BUSINESS, false)).isEmpty());
    }

    @Test
    void limitDependsOnAccountType() {
        assertTrue(threshold.evaluate(one("400000", "PEN", AccountType.CORPORATE, false)).isEmpty());
        assertEquals(1, threshold.evaluate(one("400000", "PEN", AccountType.RETAIL, false)).size());
    }

    // ---------- Unusual time ----------

    private final OperatingHoursPolicy hours = new OperatingHoursPolicy(ZoneId.of("America/Lima"),
            LocalTime.of(6, 0), LocalTime.of(22, 0), Map.of(PEN, new BigDecimal("5000")));
    private final UnusualTransactionTimeRule unusualTime = new UnusualTransactionTimeRule(hours);

    private AccountActivity at(String limaIsoLocal, String amount) {
        Instant ts = java.time.LocalDateTime.parse(limaIsoLocal).atZone(ZoneId.of("America/Lima")).toInstant();
        return new AccountActivity(ACC, List.of(ledger(1, "U1", ACC, amount, "PEN", ts)));
    }

    @Test
    void highValueAtNightIsFlaggedUsingConfiguredTimezone() {
        List<AuditFinding> f = unusualTime.evaluate(at("2026-03-10T03:30:00", "7500"));
        assertEquals(1, f.size());
        assertEquals(Severity.MEDIUM, f.get(0).severity());
    }

    @Test
    void veryHighValueAtNightIsHigh() {
        assertEquals(Severity.HIGH, unusualTime.evaluate(at("2026-03-10T23:00:00", "25000")).get(0).severity());
    }

    @Test
    void lowValueAtNightOrHighValueInDaytimeIsIgnored() {
        assertTrue(unusualTime.evaluate(at("2026-03-10T03:30:00", "4999.9999")).isEmpty());
        assertTrue(unusualTime.evaluate(at("2026-03-10T12:00:00", "90000")).isEmpty());
    }

    @Test
    void windowBoundariesAreOpenInclusiveCloseExclusive() {
        assertTrue(unusualTime.evaluate(at("2026-03-10T06:00:00", "9000")).isEmpty());   // opens
        assertEquals(1, unusualTime.evaluate(at("2026-03-10T22:00:00", "9000")).size());  // closed
        assertEquals(1, unusualTime.evaluate(at("2026-03-10T05:59:59", "9000")).size());
    }

    @Test
    void thresholdIsInclusive() {
        assertEquals(1, unusualTime.evaluate(at("2026-03-10T03:00:00", "5000")).size());
    }

    @Test
    void windowCrossingMidnightIsSupported() {
        var night = new OperatingHoursPolicy(ZoneId.of("UTC"), LocalTime.of(20, 0), LocalTime.of(4, 0), Map.of());
        assertTrue(!night.isOutsideOperatingHours(Instant.parse("2026-01-01T23:00:00Z")));
        assertTrue(!night.isOutsideOperatingHours(Instant.parse("2026-01-01T03:59:00Z")));
        assertTrue(night.isOutsideOperatingHours(Instant.parse("2026-01-01T12:00:00Z")));
    }

    @Test
    void equalOpenAndCloseMeansAlwaysOperating() {
        var always = new OperatingHoursPolicy(ZoneId.of("UTC"), LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, Map.of());
        assertTrue(!always.isOutsideOperatingHours(Instant.parse("2026-01-01T03:00:00Z")));
    }

    // ---------- Engine: no duplicate alerts ----------

    @Test
    void engineEmitsAtMostOneFindingPerReferenceAndRuleKeepingHighestSeverity() {
        AuditEngine engine = new AuditEngine(List.of(duplicates, threshold, duplicates /* registered twice on purpose */));
        var activity = new AccountActivity(ACC, List.of(
                ledger(1, "D1", ACC, "50000", "PEN", T0),
                ledger(2, "D2", ACC, "50000", "PEN", T0.plusSeconds(3))));
        List<AuditFinding> f = engine.audit(activity);
        // D1: threshold CRITICAL ; D2: threshold CRITICAL + duplicate HIGH ; duplicate rule duplicated -> still one
        assertEquals(3, f.size());
        assertEquals(1, f.stream().filter(x -> x.rule().equals(TemporalDuplicateRule.NAME)).count());
        assertEquals(2, f.stream().filter(x -> x.rule().equals(CorporateThresholdRule.NAME)).count());
    }
}
