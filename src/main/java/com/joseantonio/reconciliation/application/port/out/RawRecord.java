package com.joseantonio.reconciliation.application.port.out;

import java.util.Map;

/**
 * A source record exactly as read from the file, before any business validation.
 *
 * @param recordNumber 1-based position among the data records of the file
 * @param fields       trimmed values keyed by lower-case field name (absent when null/missing)
 * @param rawPayload   canonical JSON of the original record, stored for traceability
 */
public record RawRecord(long recordNumber, Map<String, String> fields, String rawPayload) {

    public RawRecord {
        fields = Map.copyOf(fields);
    }
}
