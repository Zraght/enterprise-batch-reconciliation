-- =====================================================================================================
-- Ledger uniqueness aligned with how the reconciliation actually behaves.
--
-- V2 made (source_name, reference_code) unique for EVERY row. That is stricter than the engine needs and stricter than
-- real ledgers allow: a reversal or a pending booking may legitimately share the reference of the movement it relates
-- to. The reconciliation pairs a settlement with the POSTED, not-yet-consumed movement of the same source and
-- reference, so the only invariant it relies on is:
--
--        at most ONE POSTED movement per (source_name, reference_code)
--
-- which is exactly what a FILTERED unique index enforces. PENDING and REVERSED rows may repeat the reference.
-- (If a core reuses the same reference for several POSTED movements, e.g. partial refunds, the reference must be made
-- unique upstream, for instance by appending the movement type; the engine cannot choose between candidates.)
--
-- A plain non-unique index keeps lookups of non-POSTED rows fast (used to explain why a settlement has no counterpart).
-- =====================================================================================================
DROP INDEX UX_il_source_reference ON internal_ledger;
GO

CREATE UNIQUE NONCLUSTERED INDEX UX_il_source_reference_posted
    ON internal_ledger (source_name, reference_code)
    WHERE status = 'POSTED';

CREATE NONCLUSTERED INDEX IX_il_source_reference
    ON internal_ledger (source_name, reference_code)
    INCLUDE (status, reconciled_batch_id);
GO
