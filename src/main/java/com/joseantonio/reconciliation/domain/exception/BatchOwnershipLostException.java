package com.joseantonio.reconciliation.domain.exception;

/**
 * Raised when a worker tries to write on behalf of a batch attempt that is no longer the current owner
 * (the batch was released as stale and re-claimed, or already finished). The stale worker must stop
 * immediately without touching any more data (fencing).
 */
public class BatchOwnershipLostException extends DomainException {

    private static final long serialVersionUID = 1L;

    public BatchOwnershipLostException(long batchId, int attempt) {
        super("BATCH_OWNERSHIP_LOST",
                "Batch " + batchId + " attempt " + attempt + " no longer owns the processing lease");
    }
}
