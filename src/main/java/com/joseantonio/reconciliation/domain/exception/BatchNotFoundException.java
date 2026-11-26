package com.joseantonio.reconciliation.domain.exception;

public class BatchNotFoundException extends DomainException {

    private static final long serialVersionUID = 1L;

    public BatchNotFoundException(long batchId) {
        super("BATCH_NOT_FOUND", "Batch " + batchId + " does not exist");
    }
}
