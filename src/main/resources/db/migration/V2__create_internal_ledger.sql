-- =====================================================================================================
-- internal_ledger: accounting movements of the core banking system (one side of the reconciliation).
-- Mandatory columns: tx_id, reference_code, account_number, amount DECIMAL(19,4), currency, tx_timestamp, status.
-- Extra columns (justified):
--   source_name          gateway/clearing house the movement settles with: defines the reconciliation scope
--   account_type         needed by the Corporate Threshold audit rule
--   pre_authorized       "without prior authorization" condition of the Corporate Threshold rule
--   reconciled_batch_id  marks the batch that consumed (paired) the entry: an entry is reconciled at most once
-- =====================================================================================================
CREATE TABLE internal_ledger (
    tx_id               BIGINT         IDENTITY(1,1) NOT NULL,
    reference_code      VARCHAR(64)    NOT NULL,
    account_number      VARCHAR(34)    NOT NULL,
    amount              DECIMAL(19,4)  NOT NULL,
    currency            CHAR(3)        NOT NULL,
    tx_timestamp        DATETIME2(3)   NOT NULL,
    status              VARCHAR(20)    NOT NULL,
    source_name         NVARCHAR(100)  NOT NULL,
    account_type        VARCHAR(20)    NOT NULL,
    pre_authorized      BIT            NOT NULL CONSTRAINT DF_il_pre_authorized DEFAULT 0,
    reconciled_batch_id BIGINT         NULL,
    CONSTRAINT PK_internal_ledger PRIMARY KEY CLUSTERED (tx_id),
    CONSTRAINT FK_il_reconciled_batch FOREIGN KEY (reconciled_batch_id) REFERENCES reconciliation_batches (batch_id),
    CONSTRAINT CK_il_amount       CHECK (amount > 0),
    CONSTRAINT CK_il_status       CHECK (status IN ('PENDING','POSTED','REVERSED')),
    CONSTRAINT CK_il_account_type CHECK (account_type IN ('RETAIL','BUSINESS','CORPORATE'))
);

-- A business reference is unique inside one source (repeated references are rejected, never silently merged).
CREATE UNIQUE NONCLUSTERED INDEX UX_il_source_reference
    ON internal_ledger (source_name, reference_code);

-- Orphan pass: unreconciled POSTED entries of a source, keyset-paginated by tx_id.
CREATE NONCLUSTERED INDEX IX_il_scope
    ON internal_ledger (source_name, status, reconciled_batch_id, tx_id);

-- Audit pass: ordered scan per account.
CREATE NONCLUSTERED INDEX IX_il_account_time
    ON internal_ledger (account_number, tx_timestamp);

CREATE NONCLUSTERED INDEX IX_il_reconciled_batch
    ON internal_ledger (reconciled_batch_id)
    WHERE reconciled_batch_id IS NOT NULL;
