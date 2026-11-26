package com.joseantonio.reconciliation.domain;

import com.joseantonio.reconciliation.domain.model.AccountActivity;
import com.joseantonio.reconciliation.domain.model.AccountType;
import com.joseantonio.reconciliation.domain.model.AuditFinding;
import com.joseantonio.reconciliation.domain.model.LedgerEntry;
import com.joseantonio.reconciliation.domain.rule.AuditEngine;
import com.joseantonio.reconciliation.domain.rule.CorporateThresholdRule;
import com.joseantonio.reconciliation.domain.rule.TemporalDuplicateRule;
import com.joseantonio.reconciliation.domain.rule.ThresholdPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.joseantonio.reconciliation.testsupport.TestData.T0;
import static com.joseantonio.reconciliation.testsupport.TestData.ledger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Temporal duplicates with history: the twin may belong to another batch, source or reconciliation state. */
class AuditHistoryRulesTest {

    private static final String ACC = "ACC-0001";
    private final TemporalDuplicateRule rule = new TemporalDuplicateRule(Duration.ofSeconds(30));

    private static LedgerEntry e(long id, String ref, long seconds) {
        return ledger(id, ref, ACC, "100.00", "PEN", T0.plusSeconds(seconds));
    }

    @Test
    void theAuditedLaterMovementIsFlaggedAgainstAnEarlierHistoryMovement() {
        var activity = new AccountActivity(ACC, List.of(e(2, "LATER", 10)), List.of(e(1, "EARLIER", 0)));
        List<AuditFinding> findings = rule.evaluate(activity);
        assertEquals(1, findings.size());
        assertEquals("LATER", findings.get(0).reference().value());
        assertEquals(2L, findings.get(0).ledgerTxId());
        assertTrue(findings.get(0).description().contains("EARLIER"));
    }

    @Test
    void theAuditedEarlierMovementIsNotFlaggedBecauseOfALaterHistoryMovement() {
        var activity = new AccountActivity(ACC, List.of(e(1, "EARLIER", 0)), List.of(e(2, "LATER", 10)));
        assertTrue(rule.evaluate(activity).isEmpty(), "the later one will be flagged when it is audited");
    }

    @Test
    void historyOnlyMovementsAreNeverFlaggedByThisRun() {
        var activity = new AccountActivity(ACC, List.of(e(9, "OTHER", 5000)),
                List.of(e(1, "A", 0), e(2, "B", 10)));
        assertTrue(rule.evaluate(activity).isEmpty());
    }

    @Test
    void aMovementPresentInBothListsIsNotCountedTwiceNorComparedWithItself() {
        var same = e(1, "A", 0);
        var activity = new AccountActivity(ACC, List.of(same), List.of(same));
        assertTrue(activity.history().isEmpty(), "the audited movement is removed from history");
        assertTrue(rule.evaluate(activity).isEmpty());
    }

    @Test
    void historyOutsideTheWindowOrWithAnotherAmountIsIgnored() {
        var farther = e(1, "FAR", -30);                      // exactly the window: not a duplicate
        var other = ledger(2, "DIFF", ACC, "100.01", "PEN", T0.plusSeconds(-5));
        var activity = new AccountActivity(ACC, List.of(e(3, "NOW", 0)), List.of(farther, other));
        assertTrue(rule.evaluate(activity).isEmpty());
    }

    @Test
    void theEngineExposesTheLongestLookbackOfItsRules() {
        assertEquals(Duration.ofSeconds(30), new AuditEngine(List.of(rule)).historyLookback());
        var threshold = new CorporateThresholdRule(new ThresholdPolicy(Map.of(AccountType.RETAIL, Map.of())));
        assertEquals(Duration.ZERO, new AuditEngine(List.of(threshold)).historyLookback());
        assertEquals(Duration.ofSeconds(30), new AuditEngine(List.of(threshold, rule)).historyLookback());
    }

    @Test
    void engineKeysFindingsByTheMovementSoTwoSourcesWithTheSameReferenceAreNotMerged() {
        var a = ledger(1, "SAME-REF", ACC, "100.00", "PEN", T0);
        var b = ledger(2, "SAME-REF", ACC, "100.00", "PEN", T0.plusSeconds(5));
        var c = ledger(3, "SAME-REF", ACC, "100.00", "PEN", T0.plusSeconds(8));
        List<AuditFinding> findings = new AuditEngine(List.of(rule)).audit(new AccountActivity(ACC, List.of(a, b, c)));
        assertEquals(List.of(2L, 3L), findings.stream().map(AuditFinding::ledgerTxId).toList());
    }
}
