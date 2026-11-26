-- =====================================================================================================
-- 1. Reconciliation ACROSS batches.
--    A discrepancy can be closed by a later batch:
--      MISSING_EXTERNAL  closed when a later file settles the movement (late settlement)
--      MISSING_INTERNAL  closed when the movement is booked afterwards and paired with that settlement (late booking)
--    The original result is never rewritten (the report of the batch stays what it found); it is marked as resolved.
--
-- 2. Integrity of results: a ledger movement / a settlement has at most one result per batch.
--
-- 3. Alert identity: a ledger movement raises each audit rule at most once in its whole life, whatever the number of
--    batches that audit it (a movement can be audited again when it is reconciled by a later file).
--
-- 4. Index on external_settlements(external_ref) for the cross-batch lookups (the existing unique index is
--    batch-scoped and cannot be used to search a reference across batches).
-- =====================================================================================================
ALTER TABLE reconciliation_results ADD
    resolved_by_batch_id BIGINT       NULL,
    resolved_at          DATETIME2(3) NULL;
GO

ALTER TABLE reconciliation_results ADD CONSTRAINT FK_rr_resolved_by
    FOREIGN KEY (resolved_by_batch_id) REFERENCES reconciliation_batches (batch_id);

ALTER TABLE reconciliation_results ADD CONSTRAINT CK_rr_resolved CHECK (
    (resolved_by_batch_id IS NULL AND resolved_at IS NULL)
    OR (resolved_by_batch_id IS NOT NULL AND resolved_at IS NOT NULL));

ALTER TABLE reconciliation_results ADD CONSTRAINT CK_rr_resolved_only_orphans CHECK (
    resolved_by_batch_id IS NULL OR match_status IN ('MISSING_EXTERNAL', 'MISSING_INTERNAL'));
GO

CREATE UNIQUE NONCLUSTERED INDEX UX_rr_batch_ledger
    ON reconciliation_results (batch_id, ledger_tx_id)
    WHERE ledger_tx_id IS NOT NULL;

CREATE UNIQUE NONCLUSTERED INDEX UX_rr_batch_settlement
    ON reconciliation_results (batch_id, settlement_id)
    WHERE settlement_id IS NOT NULL;

-- Resolution look-ups touch only OPEN orphans.
CREATE NONCLUSTERED INDEX IX_rr_open_missing_external
    ON reconciliation_results (ledger_tx_id)
    WHERE match_status = 'MISSING_EXTERNAL' AND resolved_by_batch_id IS NULL;

CREATE NONCLUSTERED INDEX IX_rr_open_missing_internal
    ON reconciliation_results (settlement_id)
    WHERE match_status = 'MISSING_INTERNAL' AND resolved_by_batch_id IS NULL;

-- Purge of a batch reverts the resolutions it had made.
CREATE NONCLUSTERED INDEX IX_rr_resolved_by
    ON reconciliation_results (resolved_by_batch_id)
    WHERE resolved_by_batch_id IS NOT NULL;

CREATE NONCLUSTERED INDEX IX_es_external_ref
    ON external_settlements (external_ref)
    INCLUDE (batch_id);
GO

ALTER TABLE audit_alerts ADD ledger_tx_id BIGINT NULL;
GO

ALTER TABLE audit_alerts ADD CONSTRAINT FK_aa_ledger
    FOREIGN KEY (ledger_tx_id) REFERENCES internal_ledger (tx_id);
GO

CREATE UNIQUE NONCLUSTERED INDEX UX_aa_ledger_rule
    ON audit_alerts (ledger_tx_id, rule_triggered)
    WHERE ledger_tx_id IS NOT NULL;
GO
