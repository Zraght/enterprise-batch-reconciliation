package com.joseantonio.reconciliation.application.service;

import java.time.Duration;

/**
 * Business policy of the reconciliation scope.
 *
 * @param settlementLag how long after booking a gateway is allowed to take to settle a movement. A ledger movement is
 *                      only reported as "missing from the file" once the file's settlement period extends past
 *                      {@code booking time + settlementLag}; before that it is pending settlement (deferred).
 *                      {@code PT0S} = same-day settlement; {@code P1D} = T+1 (default).
 */
public record ReconciliationPolicy(Duration settlementLag) {

    public ReconciliationPolicy {
        if (settlementLag == null || settlementLag.isNegative()) {
            throw new IllegalArgumentException("settlementLag must be zero or positive");
        }
    }

    public static ReconciliationPolicy defaults() {
        return new ReconciliationPolicy(Duration.ofDays(1));
    }
}
