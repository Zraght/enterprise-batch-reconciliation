package com.joseantonio.reconciliation.application.port.in;

public interface RecoverBatchesUseCase {

    RecoveryReport recover();

    record RecoveryReport(int releasedStale, int dispatched) {
    }
}
