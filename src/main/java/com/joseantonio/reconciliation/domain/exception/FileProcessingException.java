package com.joseantonio.reconciliation.domain.exception;

/**
 * Structural problem with an input file that makes it impossible to continue reading it
 * (malformed JSON/CSV, missing mandatory columns, empty file, too many invalid records...).
 * Row-level problems are NOT reported with this exception; they become {@code InvalidRecord}s.
 */
public class FileProcessingException extends DomainException {

    private static final long serialVersionUID = 1L;

    public FileProcessingException(String code, String message) {
        super(code, message);
    }

    public FileProcessingException(String code, String message, Throwable cause) {
        super(code, message, cause);
    }
}
