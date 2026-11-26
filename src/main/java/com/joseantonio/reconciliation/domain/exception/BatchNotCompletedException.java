package com.joseantonio.reconciliation.domain.exception;

import com.joseantonio.reconciliation.domain.model.BatchStatus;

/** Results were requested for a batch that has not finished successfully. */
public class BatchNotCompletedException extends DomainException {

    private static final long serialVersionUID = 1L;

    private final BatchStatus status;

    public BatchNotCompletedException(long batchId, BatchStatus status) {
        super("BATCH_NOT_COMPLETED",
                "Batch " + batchId + " is " + status + "; results are only available for COMPLETED batches. "
                        + "Use the status endpoint to follow its progress.");
        this.status = status;
    }

    public BatchStatus status() {
        return status;
    }
}
