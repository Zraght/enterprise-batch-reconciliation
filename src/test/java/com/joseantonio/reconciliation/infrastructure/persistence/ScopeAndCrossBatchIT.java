package com.joseantonio.reconciliation.infrastructure.persistence;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reconciliation scope and reconciliation across batches against the real database (settlement lag is PT0S in the
 * "test" profile, so deferral here comes from the declared settlement period).
 */
@AutoConfigureMockMvc
class ScopeAndCrossBatchIT extends AbstractSqlServerIT {

    private static final String HEADER = "external_ref,amount,currency,settlement_timestamp\n";

    @Autowired
    MockMvc mvc;

    private MvcResult upload(String source, String body, String periodStart, String periodEnd) throws Exception {
        var request = multipart("/api/v1/batches/upload")
                .file(new MockMultipartFile("file", "s.csv", "text/csv", (HEADER + body).getBytes(StandardCharsets.UTF_8)))
                .param("sourceName", source);
        if (periodStart != null) {
            request.param("periodStart", periodStart).param("periodEnd", periodEnd);
        }
        return mvc.perform(request).andReturn();
    }

    private long batchId(MvcResult r) throws Exception {
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.batchId")).longValue();
    }

    private void awaitCompleted(long id) throws Exception {
        long deadline = System.nanoTime() + 90_000_000_000L;
        while (System.nanoTime() < deadline) {
            String body = mvc.perform(get("/api/v1/batches/{id}/status", id)).andReturn().getResponse().getContentAsString();
            String status = JsonPath.read(body, "$.status");
            if (status.equals("COMPLETED")) {
                return;
            }
            if (status.equals("FAILED")) {
                fail("Batch " + id + " failed: " + JsonPath.read(body, "$.failureReason"));
            }
            Thread.sleep(100);
        }
        fail("Batch " + id + " did not finish in time");
    }

    private static String row(String ref, String amount, String ts) {
        return ref + "," + amount + ",PEN," + ts + "\n";
    }

    @Test
    void onlyMovementsDueForTheDeclaredPeriodAreReportedAndTheRestIsDeferred() throws Exception {
        String source = uniqueSource("SCOPE");
        insertLedger(source, "OVERDUE", "ACC-0001", "10.00", "PEN", "2026-03-01T10:00:00");
        insertLedger(source, "RECENT", "ACC-0002", "20.00", "PEN", "2026-03-10T10:00:00");
        MvcResult up = upload(source, row("UNKNOWN", "5.00", "2026-03-03T12:00:00Z"),
                "2026-03-01T00:00:00Z", "2026-03-05T00:00:00Z");
        long id = batchId(up);
        awaitCompleted(id);

        mvc.perform(get("/api/v1/batches/{id}/summary", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodSource").value("DECLARED"))
                .andExpect(jsonPath("$.dueCutoff").value("2026-03-05T00:00:00Z"))
                .andExpect(jsonPath("$.deferredLedgerEntries").value(1))
                .andExpect(jsonPath("$.discrepanciesByStatus.MISSING_EXTERNAL").value(1))
                .andExpect(jsonPath("$.discrepanciesByStatus.MISSING_INTERNAL").value(1));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_results WHERE batch_id = ? "
                + "AND reference_code = 'RECENT'", Integer.class, id), "RECENT is pending settlement: no result at all");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_results WHERE batch_id = ? "
                + "AND reference_code = 'OVERDUE' AND match_status = 'MISSING_EXTERNAL'", Integer.class, id));
    }

    @Test
    void aLateSettlementClosesTheEarlierDiscrepancy() throws Exception {
        String source = uniqueSource("LATE");
        insertLedger(source, "OVERDUE", "ACC-0001", "10.00", "PEN", "2026-03-01T10:00:00");
        long first = batchId(upload(source, row("UNKNOWN", "5.00", "2026-03-03T12:00:00Z"),
                "2026-03-01T00:00:00Z", "2026-03-05T00:00:00Z"));
        awaitCompleted(first);
        mvc.perform(get("/api/v1/batches/{id}/summary", first)).andExpect(jsonPath("$.openDiscrepancies").value(2));

        long second = batchId(upload(source, row("OVERDUE", "10.00", "2026-03-06T09:00:00Z"), null, null));
        awaitCompleted(second);

        mvc.perform(get("/api/v1/batches/{id}/summary", first))
                .andExpect(jsonPath("$.discrepancyCount").value(2))
                .andExpect(jsonPath("$.resolvedByLaterBatches").value(1))
                .andExpect(jsonPath("$.openDiscrepancies").value(1));
        mvc.perform(get("/api/v1/batches/{id}/discrepancies", first).param("resolved", "true"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].referenceCode").value("OVERDUE"))
                .andExpect(jsonPath("$.content[0].resolvedByBatchId").value(second));
        mvc.perform(get("/api/v1/batches/{id}/discrepancies", first).param("resolved", "false"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void aLateBookingClosesTheEarlierMissingInternal() throws Exception {
        String source = uniqueSource("BOOK");
        long first = batchId(upload(source, row("X", "40.00", "2026-03-10T12:00:00Z"), null, null));
        awaitCompleted(first);
        mvc.perform(get("/api/v1/batches/{id}/discrepancies", first).param("matchStatus", "MISSING_INTERNAL"))
                .andExpect(jsonPath("$.totalElements").value(1));

        insertLedger(source, "X", "ACC-0001", "40.00", "PEN", "2026-03-10T13:00:00"); // the core posts it afterwards
        long second = batchId(upload(source, row("OTHER", "1.00", "2026-03-12T09:00:00Z"), null, null));
        awaitCompleted(second);

        assertEquals("MATCHED", jdbc.queryForObject("SELECT match_status FROM reconciliation_results "
                + "WHERE batch_id = ? AND reference_code = 'X'", String.class, second));
        mvc.perform(get("/api/v1/batches/{id}/discrepancies", first).param("resolved", "true"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].resolvedByBatchId").value(second));
    }

    @Test
    void aDifferentPeriodForTheSameFileIsAConflictAndAnInvalidOneIsABadRequest() throws Exception {
        String source = uniqueSource("PCONF");
        String body = row("A", "1.00", "2026-03-03T12:00:00Z");
        MvcResult first = upload(source, body, "2026-03-01T00:00:00Z", "2026-03-05T00:00:00Z");
        awaitCompleted(batchId(first));
        assertEquals(200, upload(source, body, "2026-03-01T00:00:00Z", "2026-03-05T00:00:00Z").getResponse().getStatus());
        MvcResult conflict = upload(source, body, "2026-03-01T00:00:00Z", "2026-03-06T00:00:00Z");
        assertEquals(409, conflict.getResponse().getStatus());
        assertEquals("BATCH_PERIOD_CONFLICT", JsonPath.read(conflict.getResponse().getContentAsString(), "$.code"));
        assertEquals(400, upload(uniqueSource("PBAD"), body, "2026-03-05T00:00:00Z", "2026-03-01T00:00:00Z")
                .getResponse().getStatus());
    }

    @Test
    void aTemporalDuplicateWhoseTwinWasReconciledInAnotherBatchIsStillDetectedOnce() throws Exception {
        String source = uniqueSource("HIST");
        insertLedger(source, "E1", "ACC-7001", "100.00", "PEN", "2026-03-10T15:00:00");
        insertLedger(source, "E2", "ACC-7001", "100.00", "PEN", "2026-03-10T15:00:10");
        long first = batchId(upload(source, row("E1", "100.00", "2026-03-10T15:00:05Z"), null, null)); // E2 not due yet
        awaitCompleted(first);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_alerts WHERE batch_id = ?", Integer.class, first));

        long second = batchId(upload(source, row("E2", "100.00", "2026-03-10T15:00:20Z"), null, null));
        awaitCompleted(second);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_alerts WHERE batch_id = ? AND reference_code = 'E2' "
                + "AND rule_triggered = 'TEMPORAL_DUPLICATE' AND ledger_tx_id IS NOT NULL", Integer.class, second));
        mvc.perform(get("/api/v1/batches/{id}/summary", second)).andExpect(jsonPath("$.alertsGenerated").value(1));
    }
}
