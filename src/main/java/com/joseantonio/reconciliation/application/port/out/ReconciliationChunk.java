package com.joseantonio.reconciliation.application.port.out;

import com.joseantonio.reconciliation.domain.model.ReconciliationOutcome;

import java.util.List;
import java.util.Set;

/**
 * One atomic unit of reconciliation.
 *
 * @param consumedLedgerTxIds   ledger movements paired in this chunk; marked as reconciled by the batch in the same
 *                              transaction as the results. Any earlier batch that had reported one of them as
 *                              MISSING_EXTERNAL gets that discrepancy marked as resolved by this batch.
 * @param resolvedSettlementIds settlements received by EARLIER batches and reported there as MISSING_INTERNAL that this
 *                              chunk paired late with a freshly booked movement; those discrepancies are marked resolved
 */
public record ReconciliationChunk(
        List<ReconciliationOutcome> outcomes,
        Set<Long> consumedLedgerTxIds,
        Set<Long> resolvedSettlementIds,
        int progressPercent) {

    public ReconciliationChunk {
        outcomes = List.copyOf(outcomes);
        consumedLedgerTxIds = Set.copyOf(consumedLedgerTxIds);
        resolvedSettlementIds = Set.copyOf(resolvedSettlementIds);
    }

    public ReconciliationChunk(List<ReconciliationOutcome> outcomes, Set<Long> consumedLedgerTxIds, int progressPercent) {
        this(outcomes, consumedLedgerTxIds, Set.of(), progressPercent);
    }
}
