package com.joseantonio.reconciliation.application.service;

import com.joseantonio.reconciliation.application.port.in.ProcessBatchUseCase;
import com.joseantonio.reconciliation.application.port.out.BatchRepositoryPort;
import com.joseantonio.reconciliation.application.port.out.ClaimedBatch;
import com.joseantonio.reconciliation.domain.exception.BatchOwnershipLostException;
import com.joseantonio.reconciliation.domain.exception.DomainException;
import com.joseantonio.reconciliation.domain.model.BatchPhase;
import org.slf4j.Logger;
import org.slf4j.MDC;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Orchestrates the lifecycle of one batch: claim -> purge leftovers -> ingest -> reconcile -> audit -> complete.
 * <p>
 * Deliberately NOT transactional: each phase commits chunk by chunk (bounded locks and log growth). Consistency is
 * guaranteed by (a) the exclusive lease with a fencing attempt number, (b) atomic chunks, and (c) restart = purge
 * + replay, which makes the whole pipeline idempotent.
 */
public final class BatchProcessingService implements ProcessBatchUseCase {

    private static final Logger log = LoggerFactory.getLogger(BatchProcessingService.class);
    private static final int MAX_REASON = 1900;

    private final BatchRepositoryPort batches;
    private final SettlementIngestionService ingestion;
    private final ReconciliationService reconciliation;
    private final AuditService audit;

    public BatchProcessingService(BatchRepositoryPort batches, SettlementIngestionService ingestion,
                                  ReconciliationService reconciliation, AuditService audit) {
        this.batches = batches;
        this.ingestion = ingestion;
        this.reconciliation = reconciliation;
        this.audit = audit;
    }

    @Override
    public void process(long batchId) {
        Optional<ClaimedBatch> claim = batches.tryClaim(batchId);
        if (claim.isEmpty()) {
            log.info("Batch {} was not claimed (not PENDING, or its source is busy); it stays queued", batchId);
            return;
        }
        ClaimedBatch batch = claim.get();
        MDC.put("batchId", String.valueOf(batchId));
        try {
            log.info("Processing batch {} (attempt {}, source {})", batchId, batch.attempt(), batch.sourceName());
            batches.purgeDerivedData(batch);
            var ingested = ingestion.ingest(batch);
            batches.updateProgress(batch, BatchPhase.RECONCILIATION, 40);
            reconciliation.reconcile(batch, ingested.validRecords(), ingested.earliest(), ingested.latest());
            batches.updateProgress(batch, BatchPhase.AUDIT, 80);
            audit.audit(batch);
            batches.markCompleted(batch);
            log.info("Batch {} COMPLETED", batchId);
        } catch (BatchOwnershipLostException e) {
            log.warn("Batch {} attempt {} lost its lease; stopping without further writes", batchId, batch.attempt());
        } catch (Exception e) {
            fail(batch, e);
        } finally {
            MDC.remove("batchId");
        }
    }

    private void fail(ClaimedBatch batch, Exception e) {
        String reason;
        if (e instanceof DomainException de) {
            reason = de.code() + ": " + de.getMessage();
            log.warn("Batch {} FAILED: {}", batch.batchId(), reason);
        } else {
            reason = "INTERNAL_ERROR: unexpected failure while processing; see server logs for batch " + batch.batchId();
            log.error("Batch {} FAILED unexpectedly", batch.batchId(), e);
        }
        try {
            batches.markFailed(batch, reason.length() > MAX_REASON ? reason.substring(0, MAX_REASON) : reason);
        } catch (RuntimeException ex) {
            log.error("Could not mark batch {} as FAILED; stale-batch recovery will pick it up", batch.batchId(), ex);
        }
    }
}
