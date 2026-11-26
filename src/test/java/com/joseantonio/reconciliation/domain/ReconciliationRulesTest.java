package com.joseantonio.reconciliation.domain;

import com.joseantonio.reconciliation.domain.model.MatchStatus;
import com.joseantonio.reconciliation.domain.model.ReconciliationCandidate;
import com.joseantonio.reconciliation.domain.model.ReconciliationOutcome;
import com.joseantonio.reconciliation.domain.model.ReferenceCode;
import com.joseantonio.reconciliation.domain.rule.AmountMismatchRule;
import com.joseantonio.reconciliation.domain.rule.ExactMatchRule;
import com.joseantonio.reconciliation.domain.rule.OrphanDetectionRule;
import com.joseantonio.reconciliation.domain.rule.ReconciliationEngine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.joseantonio.reconciliation.testsupport.TestData.T0;
import static com.joseantonio.reconciliation.testsupport.TestData.ledger;
import static com.joseantonio.reconciliation.testsupport.TestData.settlement;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconciliationRulesTest {

    private final ReconciliationEngine engine = ReconciliationEngine.standard();

    private static ReconciliationCandidate pair(String ref, String ledgerAmt, String ledgerCur, String setAmt, String setCur) {
        return new ReconciliationCandidate(ReferenceCode.of(ref),
                ledger(1, ref, "ACC-0001", ledgerAmt, ledgerCur, T0), settlement(10, ref, setAmt, setCur));
    }

    @Test
    void exactMatchWhenReferenceAmountAndCurrencyAgree() {
        ReconciliationOutcome o = engine.reconcile(pair("R1", "100.00", "PEN", "100.0000", "PEN"));
        assertEquals(MatchStatus.MATCHED, o.status());
        assertEquals(0, o.discrepancyAmount().signum());
        assertEquals(ExactMatchRule.NAME, o.ruleName());
        assertEquals(1L, o.ledgerTxId());
        assertEquals(10L, o.settlementId());
        assertTrue(!o.isDiscrepancy());
    }

    @Test
    void amountMismatchReportsSignedDifferenceSettlementMinusLedger() {
        ReconciliationOutcome o = engine.reconcile(pair("R2", "100.00", "PEN", "99.9000", "PEN"));
        assertEquals(MatchStatus.AMOUNT_MISMATCH, o.status());
        assertEquals(new BigDecimal("-0.1000"), o.discrepancyAmount());
        assertEquals("PEN", o.currency().getCurrencyCode());
        assertEquals(AmountMismatchRule.NAME, o.ruleName());
    }

    @Test
    void differenceIsExactForTinyAmounts() {
        ReconciliationOutcome o = engine.reconcile(pair("R3", "0.3000", "PEN", "0.1000", "PEN"));
        assertEquals(new BigDecimal("-0.2000"), o.discrepancyAmount());
    }

    @Test
    void currencyMismatchIsAnAmountMismatchWithoutNumericDifference() {
        ReconciliationOutcome o = engine.reconcile(pair("R4", "100.00", "PEN", "100.00", "USD"));
        assertEquals(MatchStatus.AMOUNT_MISMATCH, o.status());
        assertNull(o.discrepancyAmount());
        assertNull(o.currency());
        assertTrue(o.detail().startsWith("CURRENCY_MISMATCH"));
    }

    @Test
    void settlementWithoutLedgerIsMissingInternal() {
        var candidate = new ReconciliationCandidate(ReferenceCode.of("R5"), null, settlement(11, "R5", "50", "PEN"));
        ReconciliationOutcome o = engine.reconcile(candidate);
        assertEquals(MatchStatus.MISSING_INTERNAL, o.status());
        assertEquals(new BigDecimal("50.0000"), o.discrepancyAmount());
        assertNull(o.ledgerTxId());
        assertEquals(11L, o.settlementId());
        assertEquals(OrphanDetectionRule.NAME, o.ruleName());
    }

    @Test
    void ledgerWithoutSettlementIsMissingExternalWithNegativeAmount() {
        var candidate = new ReconciliationCandidate(ReferenceCode.of("R6"), ledger(2, "R6", "ACC-0001", "75", "USD", T0), null);
        ReconciliationOutcome o = engine.reconcile(candidate);
        assertEquals(MatchStatus.MISSING_EXTERNAL, o.status());
        assertEquals(new BigDecimal("-75.0000"), o.discrepancyAmount());
        assertEquals("USD", o.currency().getCurrencyCode());
        assertNull(o.settlementId());
    }

    @Test
    void referencesAreComparedCaseInsensitively() {
        var candidate = new ReconciliationCandidate(ReferenceCode.of("abc-1"),
                ledger(1, "ABC-1", "ACC-0001", "10", "PEN", T0), settlement(2, "abc-1", "10", "PEN"));
        assertEquals(MatchStatus.MATCHED, engine.reconcile(candidate).status());
    }

    @Test
    void candidateRequiresAtLeastOneSideAndConsistentReferences() {
        assertThrows(IllegalArgumentException.class, () -> new ReconciliationCandidate(ReferenceCode.of("X"), null, null));
        assertThrows(IllegalArgumentException.class, () -> new ReconciliationCandidate(ReferenceCode.of("X"),
                ledger(1, "Y", "ACC-0001", "1", "PEN", T0), null));
    }

    @Test
    void engineIsExtensibleWithCustomRuleOrder() {
        var onlyOrphans = new ReconciliationEngine(List.of(new OrphanDetectionRule()));
        assertThrows(IllegalStateException.class, () -> onlyOrphans.reconcile(pair("R9", "1", "PEN", "1", "PEN")));
        assertThrows(IllegalArgumentException.class, () -> new ReconciliationEngine(List.of()));
    }
}
