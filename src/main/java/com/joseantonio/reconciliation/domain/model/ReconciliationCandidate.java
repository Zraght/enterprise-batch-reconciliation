package com.joseantonio.reconciliation.domain.model;

import java.util.Objects;

/**
 * The two views of one business reference. Either side may be {@code null} (orphan), never both.
 */
public record ReconciliationCandidate(ReferenceCode reference, LedgerEntry ledger, ExternalSettlement settlement) {

    public ReconciliationCandidate {
        Objects.requireNonNull(reference, "reference");
        if (ledger == null && settlement == null) {
            throw new IllegalArgumentException("A candidate needs at least one side: " + reference);
        }
        if (ledger != null && !ledger.reference().equals(reference)) {
            throw new IllegalArgumentException("Ledger entry reference does not match candidate reference " + reference);
        }
        if (settlement != null && !settlement.externalRef().equals(reference)) {
            throw new IllegalArgumentException("Settlement reference does not match candidate reference " + reference);
        }
    }
}
