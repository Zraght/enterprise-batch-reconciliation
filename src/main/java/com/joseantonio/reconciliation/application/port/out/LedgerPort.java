package com.joseantonio.reconciliation.application.port.out;

import com.joseantonio.reconciliation.domain.model.AccountActivity;
import com.joseantonio.reconciliation.domain.model.LateBooking;
import com.joseantonio.reconciliation.domain.model.LedgerEntry;
import com.joseantonio.reconciliation.domain.model.OutOfScopeLedger;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

public interface LedgerPort {

    /** Bulk insert (used by the synthetic data generator). */
    void saveAll(List<LedgerEntry> entries);

    /**
     * IN-SCOPE movements for the settlements of the batch with id in ({@code exclusive}, {@code inclusive}]: same
     * source, same reference, POSTED and not yet consumed by any batch. Time plays no role in pairing.
     */
    List<LedgerEntry> findScopeForSettlements(long batchId, String sourceName,
                                              long settlementIdExclusive, long settlementIdInclusive);

    /**
     * Movements that share a reference with those settlements but are NOT eligible (not POSTED, or already reconciled
     * by an earlier batch). Only used to explain why a settlement has no internal counterpart.
     */
    List<OutOfScopeLedger> findOutOfScopeForSettlements(long batchId, String sourceName,
                                                        long settlementIdExclusive, long settlementIdInclusive);

    /**
     * POSTED movements of the source, not consumed, whose settlement was already received by an EARLIER batch and
     * reported there as an unresolved MISSING_INTERNAL (the movement was booked late). Keyset-paginated by tx id.
     */
    List<LateBooking> findLateBookings(long batchId, String sourceName, long txIdExclusive, int limit);

    /**
     * POSTED, not-consumed movements of the source that are DUE ({@code tx_timestamp <= dueCutoff}), keyset-paginated by
     * tx id. These are the ones whose absence from the file is a discrepancy.
     */
    List<LedgerEntry> findDueUnreconciledAfter(String sourceName, Instant dueCutoff, long txIdExclusive, int limit);

    /** POSTED, not-consumed movements of the source that are still within the settlement lag (not yet due). */
    int countDeferred(String sourceName, Instant dueCutoff);

    /**
     * Streams, account by account, the ledger entries referenced by the results of the batch.
     * The consumer runs while the read cursor is open: it must not run inside an outer transaction.
     */
    void forEachAuditActivity(long batchId, Consumer<AccountActivity> consumer);

    /**
     * POSTED movements of the given accounts booked in [{@code from}, {@code to}], from ANY source, batch or
     * reconciliation state. Context for rules that compare a movement with its neighbours in time.
     */
    List<LedgerEntry> findAccountHistory(Collection<String> accountNumbers, Instant from, Instant to);
}
