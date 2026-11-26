package com.joseantonio.reconciliation.domain.model;

import java.util.Objects;

/**
 * A ledger movement that was booked AFTER its settlement had already been reported as MISSING_INTERNAL by an earlier
 * batch of the same source (the core posted late). Pairing them closes that earlier discrepancy.
 *
 * @param settlement      the settlement received by the earlier batch
 * @param originBatchId   the batch that received it
 * @param originResultId  the unresolved MISSING_INTERNAL result that this pairing resolves
 */
public record LateBooking(LedgerEntry ledger, ExternalSettlement settlement, long originBatchId, long originResultId) {

    public LateBooking {
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(settlement, "settlement");
        if (!ledger.reference().equals(settlement.externalRef())) {
            throw new IllegalArgumentException("Late booking references differ: "
                    + ledger.reference() + " vs " + settlement.externalRef());
        }
    }
}
