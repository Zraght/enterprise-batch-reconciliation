package com.joseantonio.reconciliation.domain.model;

/** A source record rejected during validation. It never aborts the batch; it is stored for follow-up. */
public record InvalidRecord(long recordNumber, String reasonCode, String message, String rawPayload) {
}
