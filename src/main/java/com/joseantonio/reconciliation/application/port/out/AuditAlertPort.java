package com.joseantonio.reconciliation.application.port.out;

import com.joseantonio.reconciliation.domain.model.AuditAlert;
import com.joseantonio.reconciliation.domain.model.Severity;

import java.util.List;
import java.util.Map;

public interface AuditAlertPort {

    /** Idempotent: an alert already stored for (batch, reference, rule) is skipped. Fenced by the batch attempt. */
    void saveAll(ClaimedBatch batch, List<AuditAlert> alerts);

    Map<Severity, Long> countBySeverity(long batchId);
}
