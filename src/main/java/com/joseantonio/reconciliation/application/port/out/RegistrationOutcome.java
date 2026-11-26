package com.joseantonio.reconciliation.application.port.out;

import com.joseantonio.reconciliation.domain.model.BatchSnapshot;

/** {@code created=false} means a batch for the same (source, file hash) already existed. */
public record RegistrationOutcome(BatchSnapshot batch, boolean created) {
}
