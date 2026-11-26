package com.joseantonio.reconciliation.application.port.in;

public interface ProcessBatchUseCase {

    /** Runs the whole pipeline of one batch. Safe to call concurrently / repeatedly: only one caller wins the claim. */
    void process(long batchId);
}
