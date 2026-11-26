package com.joseantonio.reconciliation.domain.model;

/**
 * A ledger movement that shares its reference with a settlement but is NOT eligible to be paired with it: it is not
 * POSTED (pending / reversed) or it was already reconciled by an earlier batch. Used only to explain why such a
 * settlement is reported as MISSING_INTERNAL.
 *
 * @param reconciledBatchId batch that already consumed the movement, or {@code null}
 */
public record OutOfScopeLedger(LedgerEntry entry, Long reconciledBatchId) {
}
