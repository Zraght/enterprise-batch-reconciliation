package com.joseantonio.reconciliation.application.port.out;

public interface BatchDispatchPort {

    /** Schedules asynchronous processing. Returns false when the batch is already queued/running in this node. */
    boolean dispatch(long batchId);
}
