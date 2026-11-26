package com.joseantonio.reconciliation.domain.exception;

/** A single transaction/record violates a business or format invariant (amount, currency, reference...). */
public class InvalidTransactionException extends DomainException {

    private static final long serialVersionUID = 1L;

    public InvalidTransactionException(String code, String message) {
        super(code, message);
    }
}
