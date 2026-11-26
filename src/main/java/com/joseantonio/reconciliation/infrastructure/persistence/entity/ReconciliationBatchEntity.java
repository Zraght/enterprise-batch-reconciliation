package com.joseantonio.reconciliation.infrastructure.persistence.entity;

import com.joseantonio.reconciliation.application.port.out.NewBatch;
import com.joseantonio.reconciliation.domain.model.BatchPhase;
import com.joseantonio.reconciliation.domain.model.BatchSnapshot;
import com.joseantonio.reconciliation.domain.model.BatchStatus;
import com.joseantonio.reconciliation.domain.model.FileFormat;
import com.joseantonio.reconciliation.domain.model.SettlementWindow;
import com.joseantonio.reconciliation.infrastructure.persistence.TimeConversions;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * JPA mapping of {@code reconciliation_batches}. Used to create a batch and to read it. Lifecycle transitions
 * (claim, heartbeat, completion...) are single conditional UPDATE statements issued through JDBC, because they must
 * be atomic and fenced by the attempt number, which an in-memory entity cannot guarantee.
 */
@Entity
@Table(name = "reconciliation_batches")
public class ReconciliationBatchEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "batch_id")
    private Long batchId;

    @Column(name = "execution_date", nullable = false)
    private LocalDateTime executionDate;

    @Column(name = "source_name", nullable = false, length = 100)
    private String sourceName;

    @Column(name = "total_records", nullable = false)
    private int totalRecords;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "file_name", nullable = false, length = 260)
    private String fileName;

    @Column(name = "file_format", nullable = false, length = 10)
    private String fileFormat;

    @Column(name = "file_sha256", nullable = false, length = 64)
    private String fileSha256;

    @Column(name = "file_size_bytes", nullable = false)
    private long fileSizeBytes;

    @Column(name = "stored_file_location", nullable = false, length = 1000)
    private String storedFileLocation;

    @Column(name = "valid_records", nullable = false)
    private int validRecords;

    @Column(name = "invalid_records", nullable = false)
    private int invalidRecords;

    @Column(name = "current_phase", nullable = false, length = 20)
    private String currentPhase;

    @Column(name = "progress_percent", nullable = false)
    private int progressPercent;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Column(name = "failure_reason", length = 2000)
    private String failureReason;

    @Column(name = "declared_period_start")
    private LocalDateTime declaredPeriodStart;

    @Column(name = "declared_period_end")
    private LocalDateTime declaredPeriodEnd;

    @Column(name = "period_start")
    private LocalDateTime periodStart;

    @Column(name = "period_end")
    private LocalDateTime periodEnd;

    @Column(name = "period_source", length = 10)
    private String periodSource;

    @Column(name = "due_cutoff")
    private LocalDateTime dueCutoff;

    @Column(name = "deferred_ledger_entries")
    private Integer deferredLedgerEntries;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "last_heartbeat")
    private LocalDateTime lastHeartbeat;

    protected ReconciliationBatchEntity() {
        // required by JPA
    }

    public static ReconciliationBatchEntity pending(NewBatch nb, LocalDateTime now) {
        ReconciliationBatchEntity e = new ReconciliationBatchEntity();
        e.executionDate = TimeConversions.toUtc(nb.executionDate());
        e.sourceName = nb.sourceName();
        e.status = BatchStatus.PENDING.name();
        e.fileName = nb.fileName();
        e.fileFormat = nb.fileFormat().name();
        e.fileSha256 = nb.fileSha256();
        e.fileSizeBytes = nb.fileSizeBytes();
        e.storedFileLocation = nb.storedFileLocation();
        e.currentPhase = BatchPhase.QUEUED.name();
        e.declaredPeriodStart = TimeConversions.toUtc(nb.declaredPeriodStart());
        e.declaredPeriodEnd = TimeConversions.toUtc(nb.declaredPeriodEnd());
        e.createdAt = now;
        return e;
    }

    public BatchSnapshot toSnapshot() {
        return new BatchSnapshot(batchId, TimeConversions.toInstant(executionDate), sourceName, fileName,
                FileFormat.valueOf(fileFormat), BatchStatus.valueOf(status), BatchPhase.valueOf(currentPhase),
                progressPercent, totalRecords, validRecords, invalidRecords, failureReason,
                TimeConversions.toInstant(declaredPeriodStart), TimeConversions.toInstant(declaredPeriodEnd),
                TimeConversions.toInstant(periodStart), TimeConversions.toInstant(periodEnd),
                periodSource == null ? null : SettlementWindow.Source.valueOf(periodSource),
                TimeConversions.toInstant(dueCutoff), deferredLedgerEntries,
                TimeConversions.toInstant(createdAt), TimeConversions.toInstant(startedAt),
                TimeConversions.toInstant(completedAt));
    }

    public Long getBatchId() {
        return batchId;
    }
}
