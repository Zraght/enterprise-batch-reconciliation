package com.joseantonio.reconciliation.domain.exception;

/** The request is valid but contradicts the state of an existing batch (e.g. same file, different settlement period). */
public class BatchConflictException extends DomainException {

    private static final long serialVersionUID = 1L;

    public BatchConflictException(String code, String message) {
        super(code, message);
    }
}
