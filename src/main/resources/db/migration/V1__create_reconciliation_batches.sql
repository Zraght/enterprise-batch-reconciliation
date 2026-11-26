-- =====================================================================================================
-- reconciliation_batches: one row per uploaded settlement file.
-- Mandatory columns of the specification: batch_id, execution_date, source_name, total_records, status.
-- Extra columns (justified):
--   file_*                       traceability + idempotency (same source + same SHA-256 => same batch)
--   valid_records/invalid_records/current_phase/progress_percent   progress tracking
--   attempt_no                   fencing token: every write of a worker is conditioned on it
--   last_heartbeat               crash detection (stale PROCESSING batches are released)
--   failure_reason, *_at         diagnostics
-- All timestamps are UTC (DATETIME2).
-- =====================================================================================================
CREATE TABLE reconciliation_batches (
    batch_id             BIGINT         IDENTITY(1,1) NOT NULL,
    execution_date       DATETIME2(3)   NOT NULL,
    source_name          NVARCHAR(100)  NOT NULL,
    total_records        INT            NOT NULL CONSTRAINT DF_rb_total_records   DEFAULT 0,
    status               VARCHAR(20)    NOT NULL,
    file_name            NVARCHAR(260)  NOT NULL,
    file_format          VARCHAR(10)    NOT NULL,
    file_sha256          CHAR(64)       NOT NULL,
    file_size_bytes      BIGINT         NOT NULL,
    stored_file_location NVARCHAR(1000) NOT NULL,
    valid_records        INT            NOT NULL CONSTRAINT DF_rb_valid_records   DEFAULT 0,
    invalid_records      INT            NOT NULL CONSTRAINT DF_rb_invalid_records DEFAULT 0,
    current_phase        VARCHAR(20)    NOT NULL CONSTRAINT DF_rb_phase           DEFAULT 'QUEUED',
    progress_percent     INT            NOT NULL CONSTRAINT DF_rb_progress        DEFAULT 0,
    attempt_no           INT            NOT NULL CONSTRAINT DF_rb_attempt         DEFAULT 0,
    failure_reason       NVARCHAR(2000) NULL,
    created_at           DATETIME2(3)   NOT NULL CONSTRAINT DF_rb_created_at      DEFAULT SYSUTCDATETIME(),
    started_at           DATETIME2(3)   NULL,
    completed_at         DATETIME2(3)   NULL,
    last_heartbeat       DATETIME2(3)   NULL,
    CONSTRAINT PK_reconciliation_batches PRIMARY KEY CLUSTERED (batch_id),
    CONSTRAINT CK_rb_status   CHECK (status IN ('PENDING','PROCESSING','COMPLETED','FAILED')),
    CONSTRAINT CK_rb_format   CHECK (file_format IN ('CSV','JSON')),
    CONSTRAINT CK_rb_phase    CHECK (current_phase IN ('QUEUED','INGESTION','RECONCILIATION','AUDIT','DONE')),
    CONSTRAINT CK_rb_progress CHECK (progress_percent BETWEEN 0 AND 100),
    CONSTRAINT CK_rb_counts   CHECK (total_records >= 0 AND valid_records >= 0 AND invalid_records >= 0),
    CONSTRAINT CK_rb_size     CHECK (file_size_bytes > 0)
);

-- Idempotency: the same file for the same source can only be registered once.
CREATE UNIQUE NONCLUSTERED INDEX UX_rb_source_file_hash
    ON reconciliation_batches (source_name, file_sha256);

-- Concurrency control enforced by the database itself (also across application nodes):
-- at most ONE batch per source can be PROCESSING at any time.
CREATE UNIQUE NONCLUSTERED INDEX UX_rb_single_processing_per_source
    ON reconciliation_batches (source_name)
    WHERE status = 'PROCESSING';

-- Recovery scheduler: find PENDING batches and stale PROCESSING batches quickly.
CREATE NONCLUSTERED INDEX IX_rb_status_heartbeat
    ON reconciliation_batches (status, last_heartbeat)
    INCLUDE (source_name);
