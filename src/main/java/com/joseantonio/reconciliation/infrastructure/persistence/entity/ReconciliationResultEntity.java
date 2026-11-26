package com.joseantonio.reconciliation.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Read-only view of {@code reconciliation_results}. Rows are written in bulk through JDBC (see the adapter). */
@Entity
@Immutable
@Table(name = "reconciliation_results")
public class ReconciliationResultEntity {

    @Id
    @Column(name = "result_id")
    private Long resultId;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "reference_code", nullable = false, length = 64)
    private String referenceCode;

    @Column(name = "match_status", nullable = false, length = 20)
    private String matchStatus;

    @Column(name = "discrepancy_amount", precision = 19, scale = 4)
    private BigDecimal discrepancyAmount;

    @Column(name = "currency", length = 3)
    private String currency;

    @Column(name = "ledger_tx_id")
    private Long ledgerTxId;

    @Column(name = "settlement_id")
    private Long settlementId;

    @Column(name = "detail", length = 500)
    private String detail;

    @Column(name = "resolved_by_batch_id")
    private Long resolvedByBatchId;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected ReconciliationResultEntity() {
        // required by JPA
    }

    public Long getResultId() {
        return resultId;
    }

    public Long getBatchId() {
        return batchId;
    }

    public String getReferenceCode() {
        return referenceCode;
    }

    public String getMatchStatus() {
        return matchStatus;
    }

    public BigDecimal getDiscrepancyAmount() {
        return discrepancyAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public Long getLedgerTxId() {
        return ledgerTxId;
    }

    public Long getSettlementId() {
        return settlementId;
    }

    public String getDetail() {
        return detail;
    }

    public Long getResolvedByBatchId() {
        return resolvedByBatchId;
    }
}
