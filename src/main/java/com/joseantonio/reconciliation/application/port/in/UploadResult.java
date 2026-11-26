package com.joseantonio.reconciliation.application.port.in;

import com.joseantonio.reconciliation.domain.model.BatchSnapshot;

/**
 * @param outcome CREATED = new batch queued; RETRIED = same file had FAILED and was re-queued;
 *                EXISTING = idempotent replay, nothing new was started.
 */
public record UploadResult(BatchSnapshot batch, Outcome outcome) {

    public enum Outcome { CREATED, RETRIED, EXISTING }

    public boolean startedProcessing() {
        return outcome != Outcome.EXISTING;
    }
}
