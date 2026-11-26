-- =====================================================================================================
-- external_settlements: transactions reported by gateways (Niubiz, Izipay, clearing houses).
-- Mandatory columns: settlement_id, batch_id, external_ref, amount, currency, settlement_timestamp, raw_payload.
-- Extra: record_number = position in the source file (traceability back to the exact row).
-- =====================================================================================================
CREATE TABLE external_settlements (
    settlement_id        BIGINT         IDENTITY(1,1) NOT NULL,
    batch_id             BIGINT         NOT NULL,
    external_ref         VARCHAR(64)    NOT NULL,
    amount               DECIMAL(19,4)  NOT NULL,
    currency             CHAR(3)        NOT NULL,
    settlement_timestamp DATETIME2(3)   NOT NULL,
    raw_payload          NVARCHAR(MAX)  NOT NULL,
    record_number        BIGINT         NOT NULL,
    CONSTRAINT PK_external_settlements PRIMARY KEY CLUSTERED (settlement_id),
    CONSTRAINT FK_es_batch FOREIGN KEY (batch_id) REFERENCES reconciliation_batches (batch_id),
    CONSTRAINT CK_es_amount   CHECK (amount > 0)
);

-- A reference appears at most once per batch; also the join key against internal_ledger.
CREATE UNIQUE NONCLUSTERED INDEX UX_es_batch_reference
    ON external_settlements (batch_id, external_ref);

-- Keyset pagination of a batch ordered by settlement_id.
CREATE NONCLUSTERED INDEX IX_es_batch_id
    ON external_settlements (batch_id, settlement_id);
