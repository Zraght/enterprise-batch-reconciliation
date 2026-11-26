package com.joseantonio.reconciliation.infrastructure.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Flyway really produced the schema required by the specification, with the right types and constraints. */
class MigrationIT extends AbstractSqlServerIT {

    private static final String OWN_TABLES = "('reconciliation_batches', 'internal_ledger', 'external_settlements', "
            + "'reconciliation_results', 'audit_alerts', 'batch_invalid_records')";

    @Test
    void allMigrationsAreAppliedSuccessfully() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
        assertEquals(9, rows.size());
        rows.forEach(r -> assertTrue((Boolean) r.get("success")));
    }

    @Test
    void everyRequiredTableExists() {
        List<String> tables = jdbc.queryForList(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_TYPE = 'BASE TABLE'", String.class);
        assertTrue(tables.containsAll(List.of("reconciliation_batches", "internal_ledger", "external_settlements",
                "reconciliation_results", "audit_alerts", "batch_invalid_records")), tables.toString());
    }

    @Test
    void everyMonetaryColumnIsDecimal19_4() {
        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, NUMERIC_PRECISION, NUMERIC_SCALE FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE COLUMN_NAME IN ('amount', 'discrepancy_amount') AND TABLE_NAME IN " + OWN_TABLES);
        assertEquals(3, columns.size(), columns.toString()); // ledger.amount, settlements.amount, results.discrepancy_amount
        for (Map<String, Object> c : columns) {
            assertEquals("decimal", c.get("DATA_TYPE"), c.toString());
            assertEquals(19, ((Number) c.get("NUMERIC_PRECISION")).intValue(), c.toString());
            assertEquals(4, ((Number) c.get("NUMERIC_SCALE")).intValue(), c.toString());
        }
    }

    @Test
    void noBinaryFloatingPointColumnExistsAnywhere() {
        Integer floats = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE DATA_TYPE IN ('float', 'real') AND TABLE_NAME IN "
                        + OWN_TABLES, Integer.class);
        assertEquals(0, floats);
    }

    @Test
    void ledgerReferenceIsUniquePerSource() {
        String source = uniqueSource("UQ");
        insertLedger(source, "DUP-1", "ACC-0001", "10.00", "PEN", "2026-03-10T15:00:00");
        assertThrows(DataIntegrityViolationException.class,
                () -> insertLedger(source, "DUP-1", "ACC-0002", "11.00", "PEN", "2026-03-10T15:00:00"));
        insertLedger(uniqueSource("OTHER"), "DUP-1", "ACC-0001", "10.00", "PEN", "2026-03-10T15:00:00"); // other source: fine
    }

    @Test
    void databaseRejectsNonPositiveAmountsAndInvalidStatuses() {
        String source = uniqueSource("CK");
        assertThrows(DataIntegrityViolationException.class,
                () -> insertLedger(source, "NEG-1", "ACC-0001", "-1.00", "PEN", "2026-03-10T15:00:00"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO internal_ledger (reference_code, account_number, amount, currency, tx_timestamp, status, "
                        + "source_name, account_type) VALUES ('BAD-STATUS', 'ACC-0001', 1.00, 'PEN', SYSUTCDATETIME(), "
                        + "'BOGUS', ?, 'RETAIL')", source));
    }

    @Test
    void onlyOneBatchPerSourceCanBeProcessingAtTheSameTime() {
        String source = uniqueSource("SINGLE");
        long a = insertBatch(source, "a".repeat(64), "PENDING");
        long b = insertBatch(source, "b".repeat(64), "PENDING");
        assertEquals(1, jdbc.update("UPDATE reconciliation_batches SET status = 'PROCESSING' WHERE batch_id = ?", a));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE reconciliation_batches SET status = 'PROCESSING' WHERE batch_id = ?", b));
        assertEquals(1, jdbc.update("UPDATE reconciliation_batches SET status = 'COMPLETED' WHERE batch_id = ?", a));
        assertEquals(1, jdbc.update("UPDATE reconciliation_batches SET status = 'PROCESSING' WHERE batch_id = ?", b));
    }

    @Test
    void sameFileForTheSameSourceCannotBeRegisteredTwice() {
        String source = uniqueSource("HASH");
        insertBatch(source, "c".repeat(64), "PENDING");
        assertThrows(DataIntegrityViolationException.class, () -> insertBatch(source, "c".repeat(64), "PENDING"));
        insertBatch(uniqueSource("HASH2"), "c".repeat(64), "PENDING"); // same hash, other source: fine
    }

    private long insertBatch(String source, String sha, String status) {
        jdbc.update("INSERT INTO reconciliation_batches (execution_date, source_name, status, file_name, file_format, "
                + "file_sha256, file_size_bytes, stored_file_location) VALUES (SYSUTCDATETIME(), ?, ?, 'f.csv', 'CSV', ?, 10, '/tmp/f.csv')",
                source, status, sha);
        return jdbc.queryForObject("SELECT batch_id FROM reconciliation_batches WHERE source_name = ? AND file_sha256 = ?",
                Long.class, source, sha);
    }

    // ------------------------------------------------------------------ V7..V9

    @Test
    void aReversedOrPendingMovementMayShareTheReferenceOfAPostedOneButTwoPostedMayNot() {
        String source = uniqueSource("REF");
        insertLedger(source, "SAME", "ACC-0001", "10.00", "PEN", "2026-03-10T15:00:00", "POSTED");
        insertLedger(source, "SAME", "ACC-0001", "10.00", "PEN", "2026-03-10T15:01:00", "REVERSED");
        insertLedger(source, "SAME", "ACC-0001", "10.00", "PEN", "2026-03-10T15:02:00", "PENDING");
        assertThrows(DataIntegrityViolationException.class,
                () -> insertLedger(source, "SAME", "ACC-0002", "99.00", "PEN", "2026-03-10T15:03:00", "POSTED"));
    }

    @Test
    void batchPeriodColumnsMustBeConsistent() {
        String source = uniqueSource("PERIOD");
        long id = insertBatch(source, "d".repeat(64), "PENDING");
        // only one end of the declared period
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE reconciliation_batches SET declared_period_start = SYSUTCDATETIME() WHERE batch_id = ?", id));
        // end before start
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE reconciliation_batches SET period_start = '2026-03-02', period_end = '2026-03-01', "
                        + "period_source = 'INFERRED' WHERE batch_id = ?", id));
        // unknown source
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE reconciliation_batches SET period_start = '2026-03-01', period_end = '2026-03-02', "
                        + "period_source = 'GUESSED' WHERE batch_id = ?", id));
        assertEquals(1, jdbc.update("UPDATE reconciliation_batches SET period_start = '2026-03-01', "
                + "period_end = '2026-03-02', period_source = 'DECLARED', deferred_ledger_entries = 0 WHERE batch_id = ?", id));
    }

    @Test
    void aBatchHasAtMostOneResultPerLedgerMovementAndPerSettlement() {
        String source = uniqueSource("ONE");
        long batch = insertBatch(source, "e".repeat(64), "PENDING");
        insertLedger(source, "L1", "ACC-0001", "10.00", "PEN", "2026-03-10T15:00:00");
        long ledgerId = jdbc.queryForObject("SELECT tx_id FROM internal_ledger WHERE source_name = ?", Long.class, source);
        jdbc.update("INSERT INTO reconciliation_results (batch_id, reference_code, match_status, ledger_tx_id) "
                + "VALUES (?, 'L1', 'MISSING_EXTERNAL', ?)", batch, ledgerId);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO reconciliation_results (batch_id, reference_code, match_status, ledger_tx_id) "
                        + "VALUES (?, 'OTHER-REF', 'MISSING_EXTERNAL', ?)", batch, ledgerId));
    }

    @Test
    void aResolutionMustBeCompleteAndOnlyAppliesToOrphans() {
        String source = uniqueSource("RES");
        long batch = insertBatch(source, "f".repeat(64), "COMPLETED");
        long resolver = insertBatch(source, "g".repeat(64), "PENDING");
        insertLedger(source, "L1", "ACC-0001", "10.00", "PEN", "2026-03-10T15:00:00");
        long ledgerId = jdbc.queryForObject("SELECT tx_id FROM internal_ledger WHERE source_name = ?", Long.class, source);
        jdbc.update("INSERT INTO reconciliation_results (batch_id, reference_code, match_status, ledger_tx_id) "
                + "VALUES (?, 'L1', 'MISSING_EXTERNAL', ?)", batch, ledgerId);
        // resolved_by without resolved_at
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE reconciliation_results SET resolved_by_batch_id = ? WHERE batch_id = ?", resolver, batch));
        assertEquals(1, jdbc.update("UPDATE reconciliation_results SET resolved_by_batch_id = ?, "
                + "resolved_at = SYSUTCDATETIME() WHERE batch_id = ?", resolver, batch));
        // a MATCHED result cannot be "resolved"
        long other = insertBatch(source, "h".repeat(64), "COMPLETED");
        jdbc.update("INSERT INTO reconciliation_results (batch_id, reference_code, match_status, ledger_tx_id) "
                + "VALUES (?, 'L9', 'MATCHED', ?)", other, ledgerId);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE reconciliation_results SET resolved_by_batch_id = ?, resolved_at = SYSUTCDATETIME() "
                        + "WHERE batch_id = ?", resolver, other));
    }

    @Test
    void anAlertIsUniquePerLedgerMovementAndRuleAcrossBatches() {
        String source = uniqueSource("ALERT");
        long first = insertBatch(source, "i".repeat(64), "COMPLETED");
        long second = insertBatch(source, "j".repeat(64), "COMPLETED");
        insertLedger(source, "L1", "ACC-0001", "10.00", "PEN", "2026-03-10T15:00:00");
        long ledgerId = jdbc.queryForObject("SELECT tx_id FROM internal_ledger WHERE source_name = ?", Long.class, source);
        String insert = "INSERT INTO audit_alerts (batch_id, ledger_tx_id, reference_code, rule_triggered, severity, "
                + "description, created_at) VALUES (?, ?, 'L1', 'TEMPORAL_DUPLICATE', 'HIGH', 'd', SYSUTCDATETIME())";
        jdbc.update(insert, first, ledgerId);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, second, ledgerId));
        // another rule on the same movement is fine
        jdbc.update("INSERT INTO audit_alerts (batch_id, ledger_tx_id, reference_code, rule_triggered, severity, "
                + "description, created_at) VALUES (?, ?, 'L1', 'CORPORATE_THRESHOLD', 'HIGH', 'd', SYSUTCDATETIME())",
                second, ledgerId);
    }
}
