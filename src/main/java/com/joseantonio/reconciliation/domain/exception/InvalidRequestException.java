package com.joseantonio.reconciliation.domain.exception;

/** The caller supplied an invalid request (unsupported file type, empty upload, bad paging...). */
public class InvalidRequestException extends DomainException {

    private static final long serialVersionUID = 1L;

    public InvalidRequestException(String code, String message) {
        super(code, message);
    }
}
