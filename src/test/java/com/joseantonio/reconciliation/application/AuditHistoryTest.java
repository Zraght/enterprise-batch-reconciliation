package com.joseantonio.reconciliation.application;

import com.joseantonio.reconciliation.domain.model.AuditAlert;
import com.joseantonio.reconciliation.domain.model.BatchStatus;
import com.joseantonio.reconciliation.domain.model.LedgerStatus;
import com.joseantonio.reconciliation.testsupport.Harness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.joseantonio.reconciliation.testsupport.TestData.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Historical temporal-duplicate detection through the whole pipeline (settlement lag 0, so a movement is due once the
 * file's period reaches its booking time).
 */
class AuditHistoryTest {

    private static final String HEADER = "external_ref,amount,currency,settlement_timestamp\n";
    private static final Instant T = Instant.parse("2026-03-10T15:00:00Z");

    @TempDir
    Path dir;
    Harness h;

    @BeforeEach
    void setUp() {
        h = new Harness(dir);
    }

    private static String row(String ref, String amount, Instant ts) {
        return ref + "," + amount + ",PEN," + ts + "\n";
    }

    private long run(String source, String name, String body) {
        long id = h.upload(source, name, HEADER + body).batch().batchId();
        h.processing.process(id);
        assertEquals(BatchStatus.COMPLETED, h.world.row(id).status, h.world.row(id).failure);
        return id;
    }

    @Test
    void aTwinSplitAcrossTwoBatchesIsDetectedByTheBatchThatAuditsTheLaterOne() {
        h.world.saveAll(List.of(
                entry("E1", "ACC-1111", "100.00", "PEN", T),
                entry("E2", "ACC-1111", "100.00", "PEN", T.plusSeconds(10))));
        long first = run("NIUBIZ", "a.csv", row("E1", "100.00", T.plusSeconds(5)));   // E2 is booked after the cutoff
        assertTrue(h.world.alerts(first).isEmpty(), "E2 is only history here and E1 is the earlier one");

        long second = run("NIUBIZ", "b.csv", row("E2", "100.00", T.plusSeconds(20)));
        List<AuditAlert> alerts = h.world.alerts(second);
        assertEquals(1, alerts.size());
        assertEquals("E2", alerts.get(0).reference().value());
        assertEquals("TEMPORAL_DUPLICATE", alerts.get(0).rule());
        assertEquals(h.world.txIdOf("E2"), alerts.get(0).ledgerTxId());
        assertTrue(alerts.get(0).description().contains("E1"));
    }

    @Test
    void theTwinMayComeFromAnotherSource() {
        h.world.saveAll(List.of(
                entry("IZ-1", "ACC-2222", "100.00", "PEN", T, LedgerStatus.POSTED, "IZIPAY"),
                entry("NB-1", "ACC-2222", "100.00", "PEN", T.plusSeconds(5), LedgerStatus.POSTED, "NIUBIZ")));
        long niubiz = run("NIUBIZ", "n.csv", row("NB-1", "100.00", T.plusSeconds(60)));
        assertEquals(1, h.world.alerts(niubiz).size(), "paid twice through two gateways within seconds");
        run("IZIPAY", "i.csv", row("IZ-1", "100.00", T.plusSeconds(61)));
        assertEquals(1, h.world.allAlerts().size(), "the earlier movement is never flagged for being the earlier one");
    }

    @Test
    void aMovementAuditedInTwoBatchesRaisesItsAlertOnlyOnce() {
        h.world.saveAll(List.of(
                entry("E1", "ACC-3333", "100.00", "PEN", T),
                entry("E2", "ACC-3333", "100.00", "PEN", T.plusSeconds(10))));
        // batch 1: both are overdue and absent, so both are audited (as MISSING_EXTERNAL)
        long first = run("NIUBIZ", "a.csv", row("UNRELATED", "1.00", T.plusSeconds(3600)));
        assertEquals(1, h.world.alerts(first).size());
        // batch 2: the late settlement of E2 pairs it, and it is audited again
        long second = run("NIUBIZ", "b.csv", row("E2", "100.00", T.plusSeconds(7200)));
        assertTrue(h.world.alerts(second).isEmpty());
        assertEquals(1, h.world.allAlerts().size());
    }

    @Test
    void noFalsePositives() {
        Instant base = T;
        h.world.saveAll(List.of(
                entry("B0", "ACC-3333", "100.00", "PEN", base),
                entry("Y30", "ACC-3333", "100.00", "PEN", base.plusSeconds(30)),         // exactly the window
                entry("YDIFF", "ACC-3333", "100.01", "PEN", base.plusSeconds(40)),       // other amount
                entry("YCUR", "ACC-3333", "100.00", "USD", base.plusSeconds(45)),        // other currency
                entry("YACC", "ACC-4444", "100.00", "PEN", base.plusSeconds(50)),        // other account
                entry("ZREV", "ACC-5555", "77.00", "PEN", base.plusSeconds(1995), LedgerStatus.REVERSED, "NIUBIZ"),
                entry("ZPEN", "ACC-5555", "77.00", "PEN", base.plusSeconds(1994), LedgerStatus.PENDING, "NIUBIZ"),
                entry("Z", "ACC-5555", "77.00", "PEN", base.plusSeconds(2000))));
        Instant settled = base.plusSeconds(3600);
        StringBuilder body = new StringBuilder();
        for (String ref : List.of("B0", "Y30", "YDIFF", "YACC", "Z")) {
            body.append(row(ref, ref.equals("YDIFF") ? "100.01" : "100.00".equals(ref) ? "100.00" : ref.equals("Z") ? "77.00" : "100.00", settled));
        }
        body.append("YCUR,100.00,USD," + settled + "\n");
        long id = run("NIUBIZ", "a.csv", body.toString());
        assertTrue(h.world.alerts(id).isEmpty(), h.world.alerts(id).toString());
    }

    @Test
    void manyAccountsAreEvaluatedInGroupsWithTheirOwnHistory() {
        int accounts = 40; // the harness chunk size is 3, so history is loaded in many groups
        List<com.joseantonio.reconciliation.domain.model.LedgerEntry> ledger = new ArrayList<>();
        StringBuilder first = new StringBuilder();
        StringBuilder second = new StringBuilder();
        for (int i = 0; i < accounts; i++) {
            String account = "ACC-" + (9000 + i);
            ledger.add(entry("BASE-" + i, account, "10.00", "PEN", T));
            ledger.add(entry("TWIN-" + i, account, "10.00", "PEN", T.plusSeconds(10)));
            first.append(row("BASE-" + i, "10.00", T.plusSeconds(5)));
            second.append(row("TWIN-" + i, "10.00", T.plusSeconds(20)));
        }
        h.world.saveAll(ledger);
        long b1 = run("NIUBIZ", "a.csv", first.toString());
        long b2 = run("NIUBIZ", "b.csv", second.toString());
        assertTrue(h.world.alerts(b1).isEmpty());
        assertEquals(accounts, h.world.alerts(b2).size());
    }
}
