-- =====================================================================================================
-- Settlement period and due cutoff of a batch (reconciliation scope policy, see docs/ARCHITECTURE.md).
--
--   declared_period_*        optional settlement period supplied by the uploader (both ends or none)
--   period_start/period_end  EFFECTIVE period used by the attempt: the declared one, or the earliest/latest settlement
--                            timestamp of the valid records of the file
--   period_source            DECLARED | INFERRED
--   due_cutoff               period_end - settlement lag, stored so the outcome stays explainable if the configured lag
--                            changes later: movements booked up to this instant were due for the batch
--   deferred_ledger_entries  POSTED, unconsumed movements booked after the cutoff when the batch finished: pending
--                            settlement, deliberately NOT reported as MISSING_EXTERNAL
-- =====================================================================================================
ALTER TABLE reconciliation_batches ADD
    declared_period_start   DATETIME2(3) NULL,
    declared_period_end     DATETIME2(3) NULL,
    period_start            DATETIME2(3) NULL,
    period_end              DATETIME2(3) NULL,
    period_source           VARCHAR(10)  NULL,
    due_cutoff              DATETIME2(3) NULL,
    deferred_ledger_entries INT          NULL;
GO

ALTER TABLE reconciliation_batches ADD CONSTRAINT CK_rb_declared_period CHECK (
    (declared_period_start IS NULL AND declared_period_end IS NULL)
    OR (declared_period_start IS NOT NULL AND declared_period_end IS NOT NULL AND declared_period_end >= declared_period_start));

ALTER TABLE reconciliation_batches ADD CONSTRAINT CK_rb_period CHECK (
    (period_start IS NULL AND period_end IS NULL AND period_source IS NULL)
    OR (period_start IS NOT NULL AND period_end IS NOT NULL AND period_source IS NOT NULL AND period_end >= period_start));

ALTER TABLE reconciliation_batches ADD CONSTRAINT CK_rb_period_source CHECK (
    period_source IS NULL OR period_source IN ('DECLARED', 'INFERRED'));

ALTER TABLE reconciliation_batches ADD CONSTRAINT CK_rb_deferred CHECK (
    deferred_ledger_entries IS NULL OR deferred_ledger_entries >= 0);
GO
