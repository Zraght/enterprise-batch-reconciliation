-- =====================================================================================================
-- batch_invalid_records: source rows rejected by validation (never abort a batch, kept for follow-up).
-- Not in the original specification: required to handle "registros invalidos" without losing evidence.
-- =====================================================================================================
CREATE TABLE batch_invalid_records (
    invalid_id    BIGINT         IDENTITY(1,1) NOT NULL,
    batch_id      BIGINT         NOT NULL,
    record_number BIGINT         NOT NULL,
    reason_code   VARCHAR(50)    NOT NULL,
    error_message NVARCHAR(500)  NOT NULL,
    raw_payload   NVARCHAR(4000) NULL,
    created_at    DATETIME2(3)   NOT NULL CONSTRAINT DF_bir_created_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT PK_batch_invalid_records PRIMARY KEY CLUSTERED (invalid_id),
    CONSTRAINT FK_bir_batch FOREIGN KEY (batch_id) REFERENCES reconciliation_batches (batch_id)
);

CREATE NONCLUSTERED INDEX IX_bir_batch
    ON batch_invalid_records (batch_id, record_number);
