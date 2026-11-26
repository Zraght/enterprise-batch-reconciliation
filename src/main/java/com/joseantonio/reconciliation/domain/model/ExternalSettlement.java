package com.joseantonio.reconciliation.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Transaction reported by an external gateway / clearing house.
 *
 * @param settlementId database identity; {@code null} until persisted
 * @param rawPayload   the original record, kept verbatim (as JSON) for traceability
 * @param recordNumber 1-based position of the record in the source file
 */
public record ExternalSettlement(
        Long settlementId,
        ReferenceCode externalRef,
        Money amount,
        Instant timestamp,
        String rawPayload,
        long recordNumber) {

    public ExternalSettlement {
        Objects.requireNonNull(externalRef, "externalRef");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(rawPayload, "rawPayload");
    }
}
