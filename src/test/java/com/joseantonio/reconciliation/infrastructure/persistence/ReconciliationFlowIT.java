package com.joseantonio.reconciliation.infrastructure.persistence;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Whole stack through HTTP: controller -> use cases -> async pipeline -> JDBC/JPA -> real SQL Server. */
@AutoConfigureMockMvc
class ReconciliationFlowIT extends AbstractSqlServerIT {

    private static final String HEADER = "external_ref,amount,currency,settlement_timestamp\n";
    private static final String TS = "2026-03-10T15:00:00Z";
    private static final String LEDGER_TS = "2026-03-10T15:00:00"; // 10:00 in Lima: inside operating hours

    @Autowired
    MockMvc mvc;

    // ------------------------------------------------------------------ helpers

    private MvcResult upload(String source, String fileName, String content) throws Exception {
        return mvc.perform(multipart("/api/v1/batches/upload")
                        .file(new MockMultipartFile("file", fileName, "application/octet-stream",
                                content.getBytes(StandardCharsets.UTF_8)))
                        .param("sourceName", source))
                .andReturn();
    }

    private static long batchId(MvcResult result) throws Exception {
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.batchId")).longValue();
    }

    /** Polls the status endpoint until the batch leaves the PENDING/PROCESSING states. */
    private String awaitFinished(long batchId) throws Exception {
        long deadline = System.nanoTime() + 90_000_000_000L;
        while (System.nanoTime() < deadline) {
            String body = mvc.perform(get("/api/v1/batches/{id}/status", batchId))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String status = JsonPath.read(body, "$.status");
            if (status.equals("COMPLETED") || status.equals("FAILED")) {
                return status;
            }
            Thread.sleep(100);
        }
        return fail("Batch " + batchId + " did not finish in time");
    }

    private static String row(String ref, String amount, String currency) {
        return ref + "," + amount + "," + currency + "," + TS + "\n";
    }

    // ------------------------------------------------------------------ scenarios

    @Test
    void uploadReconcilesAndExposesSummaryAndDiscrepancies() throws Exception {
        String source = uniqueSource("FLOW");
        insertLedger(source, "R1", "ACC-0001", "100.00", "PEN", LEDGER_TS);
        insertLedger(source, "R2", "ACC-0002", "200.00", "PEN", LEDGER_TS);
        insertLedger(source, "R3", "ACC-0003", "300.00", "PEN", LEDGER_TS);

        String file = HEADER + row("R1", "100.00", "PEN") + row("R2", "199.90", "PEN")
                + row("R9", "5.00", "PEN") + row("R5", "abc", "PEN");
        MvcResult uploaded = upload(source, "settlements.csv", file);
        assertEquals(202, uploaded.getResponse().getStatus());
        assertNotNull(uploaded.getResponse().getHeader("Location"));
        long id = batchId(uploaded);

        assertEquals("COMPLETED", awaitFinished(id));

        mvc.perform(get("/api/v1/batches/{id}/summary", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.totalRecords").value(4))
                .andExpect(jsonPath("$.validRecords").value(3))
                .andExpect(jsonPath("$.invalidRecords").value(1))
                .andExpect(jsonPath("$.evaluatedReferences").value(4))
                .andExpect(jsonPath("$.matchedTransactions").value(1))
                .andExpect(jsonPath("$.discrepancyCount").value(3))
                .andExpect(jsonPath("$.reconciliationRatePercent").value(25.0))
                .andExpect(jsonPath("$.discrepanciesByStatus.AMOUNT_MISMATCH").value(1))
                .andExpect(jsonPath("$.discrepanciesByStatus.MISSING_INTERNAL").value(1))
                .andExpect(jsonPath("$.discrepanciesByStatus.MISSING_EXTERNAL").value(1))
                .andExpect(jsonPath("$.totalDiscrepancyAmount.PEN").value(305.1))
                .andExpect(jsonPath("$.alertsGenerated").value(0))
                .andExpect(jsonPath("$.resolvedByLaterBatches").value(0))
                .andExpect(jsonPath("$.openDiscrepancies").value(3))
                .andExpect(jsonPath("$.periodSource").value("INFERRED"))
                .andExpect(jsonPath("$.deferredLedgerEntries").value(0))
                .andExpect(jsonPath("$.invalidRecordsByReason.INVALID_AMOUNT").value(1));

        mvc.perform(get("/api/v1/batches/{id}/discrepancies", id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get("/api/v1/batches/{id}/discrepancies", id).param("matchStatus", "MISSING_EXTERNAL"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].referenceCode").value("R3"));
        mvc.perform(get("/api/v1/batches/{id}/discrepancies", id).param("reference", "r9"))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/batches/{id}/discrepancies", id).param("page", "1").param("size", "2"))
                .andExpect(jsonPath("$.content.length()").value(1)).andExpect(jsonPath("$.totalPages").value(2));

        // exact monetary value stored with scale 4 and the right sign (settlement - ledger)
        BigDecimal diff = jdbc.queryForObject("SELECT discrepancy_amount FROM reconciliation_results "
                + "WHERE batch_id = ? AND reference_code = 'R2'", BigDecimal.class, id);
        assertEquals(new BigDecimal("-0.1000"), diff);

        // paired ledger entries are consumed by this batch; the orphan stays available
        assertEquals(id, jdbc.queryForObject("SELECT reconciled_batch_id FROM internal_ledger WHERE source_name = ? "
                + "AND reference_code = 'R1'", Long.class, source));
        assertNull(jdbc.queryForObject("SELECT reconciled_batch_id FROM internal_ledger WHERE source_name = ? "
                + "AND reference_code = 'R3'", Long.class, source));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM batch_invalid_records WHERE batch_id = ? "
                + "AND reason_code = 'INVALID_AMOUNT'", Integer.class, id));
    }

    @Test
    void uploadingTheSameFileAgainIsIdempotent() throws Exception {
        String source = uniqueSource("IDEM");
        String file = HEADER + row("A1", "10.00", "PEN");
        long first = batchId(upload(source, "a.csv", file));
        awaitFinished(first);

        MvcResult replay = upload(source, "other-name.csv", file);
        assertEquals(200, replay.getResponse().getStatus());
        assertEquals(first, batchId(replay));
        assertEquals("EXISTING", JsonPath.read(replay.getResponse().getContentAsString(), "$.outcome"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_batches WHERE source_name = ?",
                Integer.class, source));
    }

    @Test
    void auditRulesProduceNonDuplicatedAlerts() throws Exception {
        String source = uniqueSource("AUDIT");
        insertLedger(source, "D1", "ACC-7777", "100.00", "PEN", "2026-03-10T15:00:00");
        insertLedger(source, "D2", "ACC-7777", "100.00", "PEN", "2026-03-10T15:00:10"); // duplicate of D1 (10 s)
        insertLedger(source, "T1", "ACC-8888", "30000.00", "PEN", LEDGER_TS);            // 3x the RETAIL limit
        String file = HEADER + row("D1", "100.00", "PEN") + row("D2", "100.00", "PEN") + row("T1", "30000.00", "PEN");

        long id = batchId(upload(source, "audit.csv", file));
        assertEquals("COMPLETED", awaitFinished(id));

        mvc.perform(get("/api/v1/batches/{id}/summary", id))
                .andExpect(jsonPath("$.alertsGenerated").value(2))
                .andExpect(jsonPath("$.alertsBySeverity.HIGH").value(2));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_alerts WHERE batch_id = ? "
                + "AND reference_code = 'D2' AND rule_triggered = 'TEMPORAL_DUPLICATE'", Integer.class, id));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_alerts WHERE batch_id = ? "
                + "AND reference_code = 'T1' AND rule_triggered = 'CORPORATE_THRESHOLD'", Integer.class, id));
    }

    @Test
    void jsonFilesAreProcessedToo() throws Exception {
        String source = uniqueSource("JSON");
        insertLedger(source, "J1", "ACC-0001", "100.10", "PEN", LEDGER_TS);
        String json = "[{\"external_ref\":\"J1\",\"amount\":100.10,\"currency\":\"PEN\",\"settlement_timestamp\":\"" + TS + "\"},"
                + "{\"external_ref\":\"J2\",\"amount\":\"7.5\",\"currency\":\"USD\",\"settlement_timestamp\":\"" + TS + "\"}]";
        long id = batchId(upload(source, "s.json", json));
        assertEquals("COMPLETED", awaitFinished(id));
        mvc.perform(get("/api/v1/batches/{id}/summary", id))
                .andExpect(jsonPath("$.matchedTransactions").value(1))
                .andExpect(jsonPath("$.discrepanciesByStatus.MISSING_INTERNAL").value(1))
                .andExpect(jsonPath("$.totalDiscrepancyAmount.USD").value(7.5));
    }

    @Test
    void invalidUploadsAreRejectedOrFailClearly() throws Exception {
        String source = uniqueSource("BAD");
        assertEquals(400, upload(source, "empty.csv", "").getResponse().getStatus());
        MvcResult xml = upload(source, "data.xml", "<a/>");
        assertEquals(400, xml.getResponse().getStatus());
        assertEquals("UNSUPPORTED_FILE_FORMAT", JsonPath.read(xml.getResponse().getContentAsString(), "$.code"));

        String wrongHeader = "foo,bar\n1,2\n";
        MvcResult uploaded = upload(source, "wrong.csv", wrongHeader);
        long id = batchId(uploaded);
        assertEquals("FAILED", awaitFinished(id));
        mvc.perform(get("/api/v1/batches/{id}/status", id))
                .andExpect(jsonPath("$.failureReason").value(org.hamcrest.Matchers.startsWith("MISSING_REQUIRED_COLUMNS")));
        mvc.perform(get("/api/v1/batches/{id}/summary", id)).andExpect(status().isConflict());

        // the same file uploaded again is re-queued instead of being ignored
        MvcResult retry = upload(source, "wrong.csv", wrongHeader);
        assertEquals(202, retry.getResponse().getStatus());
        assertEquals("RETRIED", JsonPath.read(retry.getResponse().getContentAsString(), "$.outcome"));
        assertEquals(id, batchId(retry));
        assertEquals("FAILED", awaitFinished(id));
    }

    @Test
    void unknownBatchIs404() throws Exception {
        mvc.perform(get("/api/v1/batches/{id}/status", 987654321L))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BATCH_NOT_FOUND"));
    }

    @Test
    void seedEndpointGeneratesDataThatTheEngineReconcilesExactlyAsExpected() throws Exception {
        String source = uniqueSource("SEED");
        String body = "{\"sourceName\":\"" + source + "\",\"count\":400,\"randomSeed\":2026,\"submit\":true}";
        MvcResult seeded = mvc.perform(post("/api/v1/ledger/seed").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        String json = seeded.getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(json, "$.batchId")).longValue();
        int matched = JsonPath.read(json, "$.expected.matched");
        int mismatch = JsonPath.read(json, "$.expected.amountMismatch");
        int missingExternal = JsonPath.read(json, "$.expected.missingExternal");
        int missingInternal = JsonPath.read(json, "$.expected.missingInternal");
        int invalid = JsonPath.read(json, "$.expected.invalidRecords");

        assertEquals("COMPLETED", awaitFinished(id));
        mvc.perform(get("/api/v1/batches/{id}/summary", id))
                .andExpect(jsonPath("$.matchedTransactions").value(matched))
                .andExpect(jsonPath("$.discrepanciesByStatus.AMOUNT_MISMATCH").value(mismatch))
                .andExpect(jsonPath("$.discrepanciesByStatus.MISSING_EXTERNAL").value(missingExternal))
                .andExpect(jsonPath("$.discrepanciesByStatus.MISSING_INTERNAL").value(missingInternal))
                .andExpect(jsonPath("$.invalidRecords").value(invalid));

        // the same seed for the same source is refused instead of silently duplicating ledger data
        mvc.perform(post("/api/v1/ledger/seed").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }
}
