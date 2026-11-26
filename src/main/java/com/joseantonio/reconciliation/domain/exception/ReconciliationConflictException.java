package com.joseantonio.reconciliation.domain.exception;

/** An internal ledger entry was consumed concurrently by another reconciliation (lost update prevented). */
public class ReconciliationConflictException extends DomainException {

    private static final long serialVersionUID = 1L;

    public ReconciliationConflictException(String message) {
        super("RECONCILIATION_CONFLICT", message);
    }
}
