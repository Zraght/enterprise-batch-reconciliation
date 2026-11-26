-- =====================================================================================================
-- audit_alerts: findings of the deterministic audit rules.
-- The unique index (batch_id, reference_code, rule_triggered) guarantees no duplicated alerts.
-- reference_code and rule_triggered are NVARCHAR on purpose: the idempotent insert compares them with JDBC string
-- parameters, which the SQL Server driver sends as Unicode (nvarchar) by default. Comparing nvarchar parameters with
-- VARCHAR columns would force a column conversion and prevent index seeks (quadratic cost with many alerts).
-- =====================================================================================================
CREATE TABLE audit_alerts (
    alert_id       BIGINT         IDENTITY(1,1) NOT NULL,
    batch_id       BIGINT         NOT NULL,
    reference_code NVARCHAR(64)   NOT NULL,
    rule_triggered NVARCHAR(50)   NOT NULL,
    severity       VARCHAR(10)    NOT NULL,
    description    NVARCHAR(500)  NOT NULL,
    created_at     DATETIME2(3)   NOT NULL,
    CONSTRAINT PK_audit_alerts PRIMARY KEY CLUSTERED (alert_id),
    CONSTRAINT FK_aa_batch FOREIGN KEY (batch_id) REFERENCES reconciliation_batches (batch_id),
    CONSTRAINT CK_aa_severity CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL'))
);

CREATE UNIQUE NONCLUSTERED INDEX UX_aa_batch_reference_rule
    ON audit_alerts (batch_id, reference_code, rule_triggered);

CREATE NONCLUSTERED INDEX IX_aa_batch_severity
    ON audit_alerts (batch_id, severity);
