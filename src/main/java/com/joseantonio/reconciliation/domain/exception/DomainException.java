package com.joseantonio.reconciliation.domain.exception;

/**
 * Base class for every business error raised by the domain and application layers.
 * Carries a stable, machine-readable {@code code} that adapters (REST, persistence, logs)
 * can map without parsing messages.
 */
public class DomainException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public DomainException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
