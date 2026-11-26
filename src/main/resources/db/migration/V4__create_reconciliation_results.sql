-- =====================================================================================================
-- reconciliation_results: one row per reference evaluated by a batch.
-- Mandatory columns: result_id, batch_id, reference_code, match_status, discrepancy_amount.
-- Extra (justified):
--   currency            currency of discrepancy_amount (amounts of different currencies are never added)
--   ledger_tx_id / settlement_id   traceability to both source rows
--   detail              human readable explanation (e.g. CURRENCY_MISMATCH ...)
-- discrepancy_amount = settlement - ledger (a missing side counts as 0); NULL when currencies differ.
-- =====================================================================================================
CREATE TABLE reconciliation_results (
    result_id          BIGINT         IDENTITY(1,1) NOT NULL,
    batch_id           BIGINT         NOT NULL,
    reference_code     VARCHAR(64)    NOT NULL,
    match_status       VARCHAR(20)    NOT NULL,
    discrepancy_amount DECIMAL(19,4)  NULL,
    currency           CHAR(3)        NULL,
    ledger_tx_id       BIGINT         NULL,
    settlement_id      BIGINT         NULL,
    detail             NVARCHAR(500)  NULL,
    created_at         DATETIME2(3)   NOT NULL CONSTRAINT DF_rr_created_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT PK_reconciliation_results PRIMARY KEY CLUSTERED (result_id),
    CONSTRAINT FK_rr_batch      FOREIGN KEY (batch_id)      REFERENCES reconciliation_batches (batch_id),
    CONSTRAINT FK_rr_ledger     FOREIGN KEY (ledger_tx_id)  REFERENCES internal_ledger (tx_id),
    CONSTRAINT FK_rr_settlement FOREIGN KEY (settlement_id) REFERENCES external_settlements (settlement_id),
    CONSTRAINT CK_rr_status CHECK (match_status IN ('MATCHED','MISSING_INTERNAL','MISSING_EXTERNAL','AMOUNT_MISMATCH')),
    CONSTRAINT CK_rr_sides  CHECK (ledger_tx_id IS NOT NULL OR settlement_id IS NOT NULL)
);

-- One result per reference per batch (idempotency safety net).
CREATE UNIQUE NONCLUSTERED INDEX UX_rr_batch_reference
    ON reconciliation_results (batch_id, reference_code);

-- Discrepancy listing filtered by status, stable order by result_id.
CREATE NONCLUSTERED INDEX IX_rr_batch_status
    ON reconciliation_results (batch_id, match_status, result_id);

-- Audit scope: ledger entries referenced by the batch.
CREATE NONCLUSTERED INDEX IX_rr_batch_ledger
    ON reconciliation_results (batch_id, ledger_tx_id)
    WHERE ledger_tx_id IS NOT NULL;
