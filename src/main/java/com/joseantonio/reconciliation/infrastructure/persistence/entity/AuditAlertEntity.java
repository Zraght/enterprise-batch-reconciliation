package com.joseantonio.reconciliation.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

/** Read-only view of {@code audit_alerts}. Alerts are written idempotently through JDBC. */
@Entity
@Immutable
@Table(name = "audit_alerts")
public class AuditAlertEntity {

    @Id
    @Column(name = "alert_id")
    private Long alertId;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "ledger_tx_id")
    private Long ledgerTxId;

    @Column(name = "reference_code", nullable = false, length = 64)
    private String referenceCode;

    @Column(name = "rule_triggered", nullable = false, length = 50)
    private String ruleTriggered;

    @Column(name = "severity", nullable = false, length = 10)
    private String severity;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected AuditAlertEntity() {
        // required by JPA
    }

    public Long getAlertId() {
        return alertId;
    }

    public Long getBatchId() {
        return batchId;
    }

    public Long getLedgerTxId() {
        return ledgerTxId;
    }

    public String getReferenceCode() {
        return referenceCode;
    }

    public String getRuleTriggered() {
        return ruleTriggered;
    }

    public String getSeverity() {
        return severity;
    }

    public String getDescription() {
        return description;
    }
}
