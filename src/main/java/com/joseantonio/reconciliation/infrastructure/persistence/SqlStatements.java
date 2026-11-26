package com.joseantonio.reconciliation.infrastructure.persistence;

/**
 * Every hand-written SQL statement of the persistence adapters, in one place, so that they can be reviewed (and
 * syntax-checked against the T-SQL grammar) together. All values travel as bind parameters; the only concatenated
 * pieces are compile-time constants and the placeholder list of an IN clause.
 *
 * <p>Conventions: {@code SYSUTCDATETIME()} is the only clock (UTC, same as every DATETIME2 column); lifecycle updates are
 * single conditional statements; every statement that writes on behalf of a worker is conditioned on, or preceded in the
 * same transaction by, the ownership check {@code status = 'PROCESSING' AND attempt_no = :attempt}.</p>
 */
public final class SqlStatements {

    private SqlStatements() {
    }

    /**
     * Rows deleted/updated per purge transaction. Kept below SQL Server's lock-escalation threshold (5 000 locks per
     * statement and index) so a purge never escalates to a table lock and blocks batches of other sources.
     */
    public static final int PURGE_CHUNK = 2000;

    // =================================================================================================
    // Batch lifecycle
    // =================================================================================================

    public static final String FIND_PENDING_BATCH_IDS =
            "SELECT TOP (?) batch_id FROM reconciliation_batches WHERE status = 'PENDING' ORDER BY batch_id";

    /** The database clock decides staleness: no skew between application nodes. */
    public static final String RELEASE_STALE_BATCHES =
            "UPDATE reconciliation_batches SET status = 'PENDING', current_phase = 'QUEUED', progress_percent = 0 "
                    + "OUTPUT inserted.batch_id "
                    + "WHERE status = 'PROCESSING' AND last_heartbeat < DATEADD(SECOND, ?, SYSUTCDATETIME())";

    /** Atomic claim; fails with a unique-index violation when another batch of the same source is PROCESSING. */
    public static final String CLAIM_BATCH =
            "UPDATE reconciliation_batches SET status = 'PROCESSING', current_phase = 'INGESTION', progress_percent = 0, "
                    + "attempt_no = attempt_no + 1, failure_reason = NULL, started_at = SYSUTCDATETIME(), "
                    + "completed_at = NULL, last_heartbeat = SYSUTCDATETIME() "
                    + "OUTPUT inserted.batch_id, inserted.attempt_no, inserted.source_name, inserted.file_format, "
                    + "inserted.stored_file_location, inserted.file_size_bytes, "
                    + "inserted.declared_period_start, inserted.declared_period_end "
                    + "WHERE batch_id = ? AND status = 'PENDING'";

    public static final String RESET_FOR_RETRY =
            "UPDATE reconciliation_batches SET status = 'PENDING', current_phase = 'QUEUED', progress_percent = 0, "
                    + "failure_reason = NULL, started_at = NULL, completed_at = NULL, last_heartbeat = NULL, "
                    + "declared_period_start = ?, declared_period_end = ? "
                    + "WHERE batch_id = ? AND status = 'FAILED'";

    public static final String OWNERSHIP_HEARTBEAT =
            "UPDATE reconciliation_batches SET last_heartbeat = SYSUTCDATETIME() "
                    + "WHERE batch_id = ? AND status = 'PROCESSING' AND attempt_no = ?";

    public static final String OWNERSHIP_HEARTBEAT_WITH_PROGRESS =
            "UPDATE reconciliation_batches SET last_heartbeat = SYSUTCDATETIME(), current_phase = ?, progress_percent = ? "
                    + "WHERE batch_id = ? AND status = 'PROCESSING' AND attempt_no = ?";

    public static final String RECORD_SCOPE =
            "UPDATE reconciliation_batches SET period_start = ?, period_end = ?, period_source = ?, due_cutoff = ?, "
                    + "last_heartbeat = SYSUTCDATETIME() "
                    + "WHERE batch_id = ? AND status = 'PROCESSING' AND attempt_no = ?";

    public static final String RECORD_DEFERRED =
            "UPDATE reconciliation_batches SET deferred_ledger_entries = ?, last_heartbeat = SYSUTCDATETIME() "
                    + "WHERE batch_id = ? AND status = 'PROCESSING' AND attempt_no = ?";

    public static final String MARK_COMPLETED =
            "UPDATE reconciliation_batches SET status = 'COMPLETED', current_phase = 'DONE', progress_percent = 100, "
                    + "completed_at = SYSUTCDATETIME(), last_heartbeat = SYSUTCDATETIME() "
                    + "WHERE batch_id = ? AND status = 'PROCESSING' AND attempt_no = ?";

    public static final String MARK_FAILED =
            "UPDATE reconciliation_batches SET status = 'FAILED', failure_reason = ?, "
                    + "completed_at = SYSUTCDATETIME(), last_heartbeat = SYSUTCDATETIME() "
                    + "WHERE batch_id = ? AND status = 'PROCESSING' AND attempt_no = ?";

    /**
     * Takes the row lock of a FAILED batch and proves it is still the failed attempt that releases its claims: a retry
     * (PENDING) or a new attempt (higher attempt_no) makes the predicate fail, and the release stops.
     */
    public static final String LOCK_FAILED_BATCH =
            "UPDATE reconciliation_batches SET last_heartbeat = SYSUTCDATETIME() "
                    + "WHERE batch_id = ? AND status = 'FAILED' AND attempt_no = ?";

    // =================================================================================================
    // Purge of the data derived by a previous attempt. Every statement below runs inside a transaction that first takes
    // the batch row lock with OWNERSHIP_HEARTBEAT, so ownership cannot change until the transaction ends.
    // Dependency order: children first.
    // =================================================================================================

    public static final String PURGE_ALERTS =
            "DELETE TOP (" + PURGE_CHUNK + ") FROM audit_alerts WHERE batch_id = ?";

    /** Resolutions this batch had made on results of EARLIER batches are derived data too: reopen them. */
    public static final String PURGE_REOPEN_RESOLVED =
            "UPDATE TOP (" + PURGE_CHUNK + ") reconciliation_results SET resolved_by_batch_id = NULL, resolved_at = NULL "
                    + "WHERE resolved_by_batch_id = ?";

    public static final String PURGE_RESULTS =
            "DELETE TOP (" + PURGE_CHUNK + ") FROM reconciliation_results WHERE batch_id = ?";

    public static final String PURGE_INVALID_RECORDS =
            "DELETE TOP (" + PURGE_CHUNK + ") FROM batch_invalid_records WHERE batch_id = ?";

    public static final String PURGE_SETTLEMENTS =
            "DELETE TOP (" + PURGE_CHUNK + ") FROM external_settlements WHERE batch_id = ?";

    public static final String PURGE_LEDGER_MARKS =
            "UPDATE TOP (" + PURGE_CHUNK + ") internal_ledger SET reconciled_batch_id = NULL WHERE reconciled_batch_id = ?";

    public static final String PURGE_RESET_BATCH =
            "UPDATE reconciliation_batches SET total_records = 0, valid_records = 0, invalid_records = 0, "
                    + "period_start = NULL, period_end = NULL, period_source = NULL, due_cutoff = NULL, "
                    + "deferred_ledger_entries = NULL, last_heartbeat = SYSUTCDATETIME() "
                    + "WHERE batch_id = ? AND status = 'PROCESSING' AND attempt_no = ?";

    // =================================================================================================
    // Settlements
    // =================================================================================================

    public static final String UPDATE_INGESTION_COUNTERS =
            "UPDATE reconciliation_batches SET total_records = total_records + ?, valid_records = valid_records + ?, "
                    + "invalid_records = invalid_records + ?, current_phase = 'INGESTION', progress_percent = ?, "
                    + "last_heartbeat = SYSUTCDATETIME() "
                    + "WHERE batch_id = ? AND status = 'PROCESSING' AND attempt_no = ?";

    public static final String INSERT_SETTLEMENT =
            "INSERT INTO external_settlements "
                    + "(batch_id, external_ref, amount, currency, settlement_timestamp, raw_payload, record_number) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)";

    public static final String INSERT_INVALID_RECORD =
            "INSERT INTO batch_invalid_records (batch_id, record_number, reason_code, error_message, raw_payload) "
                    + "VALUES (?, ?, ?, ?, ?)";

    /** The raw payload is not needed by the reconciliation and can be large: it is deliberately not loaded. */
    public static final String SELECT_SETTLEMENTS_PAGE =
            "SELECT TOP (?) settlement_id, external_ref, amount, currency, settlement_timestamp, record_number "
                    + "FROM external_settlements WHERE batch_id = ? AND settlement_id > ? ORDER BY settlement_id";

    public static final String INVALID_COUNTS_BY_REASON =
            "SELECT reason_code, COUNT_BIG(*) FROM batch_invalid_records WHERE batch_id = ? GROUP BY reason_code";

    // =================================================================================================
    // Ledger
    // =================================================================================================

    public static final String LEDGER_COLUMNS =
            "l.tx_id, l.reference_code, l.account_number, l.amount, l.currency, l.tx_timestamp, l.status, "
                    + "l.source_name, l.account_type, l.pre_authorized";

    public static final String INSERT_LEDGER =
            "INSERT INTO internal_ledger (reference_code, account_number, amount, currency, tx_timestamp, status, "
                    + "source_name, account_type, pre_authorized) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /** IN-SCOPE movements: same source and reference, POSTED, not consumed. Time plays no role in pairing. */
    public static final String LEDGER_IN_SCOPE_FOR_SETTLEMENTS =
            "SELECT " + LEDGER_COLUMNS + " FROM internal_ledger l "
                    + "JOIN external_settlements s ON s.external_ref = l.reference_code "
                    + "WHERE s.batch_id = ? AND s.settlement_id > ? AND s.settlement_id <= ? "
                    + "AND l.source_name = ? AND l.status = 'POSTED' AND l.reconciled_batch_id IS NULL";

    /** Same reference but NOT eligible: not POSTED, or already consumed by a batch. Explains MISSING_INTERNAL. */
    public static final String LEDGER_OUT_OF_SCOPE_FOR_SETTLEMENTS =
            "SELECT " + LEDGER_COLUMNS + ", l.reconciled_batch_id FROM internal_ledger l "
                    + "JOIN external_settlements s ON s.external_ref = l.reference_code "
                    + "WHERE s.batch_id = ? AND s.settlement_id > ? AND s.settlement_id <= ? "
                    + "AND l.source_name = ? AND NOT (l.status = 'POSTED' AND l.reconciled_batch_id IS NULL)";

    /**
     * Late bookings: POSTED, unconsumed movements whose settlement was received by an EARLIER COMPLETED batch of the
     * same source and reported there as an OPEN MISSING_INTERNAL. When several earlier settlements qualify, the most
     * recent one is used. Parameters: limit, current batch id, source, tx id (exclusive).
     */
    public static final String LEDGER_LATE_BOOKINGS =
            "SELECT TOP (?) " + LEDGER_COLUMNS + ", x.s_id, x.s_ref, x.s_amount, x.s_currency, x.s_timestamp, "
                    + "x.s_record, x.origin_batch, x.origin_result "
                    + "FROM internal_ledger l "
                    + "CROSS APPLY ("
                    + "SELECT TOP (1) s2.settlement_id AS s_id, s2.external_ref AS s_ref, s2.amount AS s_amount, "
                    + "s2.currency AS s_currency, s2.settlement_timestamp AS s_timestamp, s2.record_number AS s_record, "
                    + "s2.batch_id AS origin_batch, r2.result_id AS origin_result "
                    + "FROM external_settlements s2 "
                    + "JOIN reconciliation_batches b ON b.batch_id = s2.batch_id AND b.status = 'COMPLETED' "
                    + "AND b.source_name = l.source_name AND b.batch_id <> ? "
                    + "JOIN reconciliation_results r2 ON r2.settlement_id = s2.settlement_id "
                    + "AND r2.match_status = 'MISSING_INTERNAL' AND r2.resolved_by_batch_id IS NULL "
                    + "WHERE s2.external_ref = l.reference_code "
                    + "ORDER BY s2.settlement_id DESC) x "
                    + "WHERE l.source_name = ? AND l.status = 'POSTED' AND l.reconciled_batch_id IS NULL AND l.tx_id > ? "
                    + "ORDER BY l.tx_id";

    /** DUE movements (booked up to the cutoff, inclusive) that nobody has consumed. Params: limit, source, cutoff, tx id. */
    public static final String LEDGER_DUE_UNRECONCILED_PAGE =
            "SELECT TOP (?) " + LEDGER_COLUMNS + " FROM internal_ledger l "
                    + "WHERE l.source_name = ? AND l.status = 'POSTED' AND l.reconciled_batch_id IS NULL "
                    + "AND l.tx_timestamp <= ? AND l.tx_id > ? ORDER BY l.tx_id";

    public static final String LEDGER_COUNT_DEFERRED =
            "SELECT COUNT(*) FROM internal_ledger "
                    + "WHERE source_name = ? AND status = 'POSTED' AND reconciled_batch_id IS NULL AND tx_timestamp > ?";

    public static final String LEDGER_AUDIT_SCOPE =
            "SELECT " + LEDGER_COLUMNS + " FROM reconciliation_results r "
                    + "JOIN internal_ledger l ON l.tx_id = r.ledger_tx_id WHERE r.batch_id = ? "
                    + "ORDER BY l.account_number, l.tx_timestamp, l.tx_id";

    /**
     * Account history. The IN list is built from {@code inPlaceholders(n)}; each placeholder is CAST to VARCHAR so the
     * comparison with the VARCHAR column happens on the parameter side and the index on (account_number, tx_timestamp) can
     * be seeked (the driver sends strings as nvarchar, which would otherwise force a conversion of the column).
     */
    public static String ledgerAccountHistory(int accounts) {
        return "SELECT " + LEDGER_COLUMNS + " FROM internal_ledger l "
                + "WHERE l.account_number IN (" + inPlaceholders(accounts) + ") AND l.status = 'POSTED' "
                + "AND l.tx_timestamp >= ? AND l.tx_timestamp <= ?";
    }

    static String inPlaceholders(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("at least one placeholder is required");
        }
        return "CAST(? AS VARCHAR(34))" + ", CAST(? AS VARCHAR(34))".repeat(n - 1);
    }

    // =================================================================================================
    // Reconciliation results
    // =================================================================================================

    public static final String INSERT_RESULT =
            "INSERT INTO reconciliation_results "
                    + "(batch_id, reference_code, match_status, discrepancy_amount, currency, ledger_tx_id, settlement_id, detail) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    /** Compare-and-set: an entry can be consumed only while nobody else has consumed it. */
    public static final String CONSUME_LEDGER =
            "UPDATE internal_ledger SET reconciled_batch_id = ? WHERE tx_id = ? AND reconciled_batch_id IS NULL";

    /** Late settlement: earlier batches' open MISSING_EXTERNAL for the movement just paired. */
    public static final String RESOLVE_MISSING_EXTERNAL =
            "UPDATE reconciliation_results SET resolved_by_batch_id = ?, resolved_at = SYSUTCDATETIME() "
                    + "WHERE ledger_tx_id = ? AND match_status = 'MISSING_EXTERNAL' AND resolved_by_batch_id IS NULL "
                    + "AND batch_id <> ?";

    /** Late booking: the earlier batch's open MISSING_INTERNAL for the settlement just paired. */
    public static final String RESOLVE_MISSING_INTERNAL =
            "UPDATE reconciliation_results SET resolved_by_batch_id = ?, resolved_at = SYSUTCDATETIME() "
                    + "WHERE settlement_id = ? AND match_status = 'MISSING_INTERNAL' AND resolved_by_batch_id IS NULL "
                    + "AND batch_id <> ?";

    // =================================================================================================
    // Audit alerts
    // =================================================================================================

    /**
     * Idempotent insert. A movement raises each rule once in its whole life (across batches), and a batch holds a
     * reference/rule pair once; the unique indexes UX_aa_ledger_rule and UX_aa_batch_reference_rule are the safety net.
     */
    public static final String INSERT_ALERT_IF_ABSENT =
            "INSERT INTO audit_alerts "
                    + "(batch_id, ledger_tx_id, reference_code, rule_triggered, severity, description, created_at) "
                    + "SELECT ?, ?, ?, ?, ?, ?, ? "
                    + "WHERE NOT EXISTS (SELECT 1 FROM audit_alerts WHERE ledger_tx_id = ? AND rule_triggered = ?) "
                    + "AND NOT EXISTS (SELECT 1 FROM audit_alerts WHERE batch_id = ? AND reference_code = ? AND rule_triggered = ?)";
}
