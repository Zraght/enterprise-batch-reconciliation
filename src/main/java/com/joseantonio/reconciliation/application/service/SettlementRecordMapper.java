package com.joseantonio.reconciliation.application.service;

import com.joseantonio.reconciliation.application.port.out.RawRecord;
import com.joseantonio.reconciliation.domain.exception.InvalidTransactionException;
import com.joseantonio.reconciliation.domain.model.ExternalSettlement;
import com.joseantonio.reconciliation.domain.model.InvalidRecord;
import com.joseantonio.reconciliation.domain.model.Money;
import com.joseantonio.reconciliation.domain.model.ReferenceCode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;

/**
 * Validates and normalises a raw record into a domain {@link ExternalSettlement}.
 * A bad record never throws: it is returned as {@link Invalid} so the batch can keep going.
 */
public final class SettlementRecordMapper {

    public static final String FIELD_REFERENCE = "external_ref";
    public static final String FIELD_AMOUNT = "amount";
    public static final String FIELD_CURRENCY = "currency";
    public static final String FIELD_TIMESTAMP = "settlement_timestamp";
    public static final Set<String> REQUIRED_FIELDS =
            Set.of(FIELD_REFERENCE, FIELD_AMOUNT, FIELD_CURRENCY, FIELD_TIMESTAMP);

    /** Marker injected by file readers when a row is structurally broken (e.g. wrong number of columns). */
    public static final String ROW_ERROR_FIELD = "_row_error";

    private static final int MAX_STORED_PAYLOAD = 4000;

    public sealed interface Parsed permits Valid, Invalid {
    }

    public record Valid(ExternalSettlement settlement) implements Parsed {
    }

    public record Invalid(InvalidRecord record) implements Parsed {
    }

    public Parsed map(RawRecord raw) {
        Map<String, String> f = raw.fields();
        if (f.containsKey(ROW_ERROR_FIELD)) {
            return new Invalid(invalid(raw, "MALFORMED_ROW", f.get(ROW_ERROR_FIELD)));
        }
        try {
            ReferenceCode reference = ReferenceCode.of(f.get(FIELD_REFERENCE));
            Money amount = Money.of(f.get(FIELD_AMOUNT), f.get(FIELD_CURRENCY));
            Instant timestamp = parseTimestamp(f.get(FIELD_TIMESTAMP));
            return new Valid(new ExternalSettlement(null, reference, amount, timestamp, raw.rawPayload(), raw.recordNumber()));
        } catch (InvalidTransactionException e) {
            return new Invalid(invalid(raw, e.code(), e.getMessage()));
        }
    }

    public static InvalidRecord invalid(RawRecord raw, String code, String message) {
        return new InvalidRecord(raw.recordNumber(), code, truncate(message, 500), truncate(raw.rawPayload(), MAX_STORED_PAYLOAD));
    }

    /** ISO-8601 with mandatory offset or 'Z'. Stored with millisecond precision (DATETIME2(3)). */
    static Instant parseTimestamp(String text) {
        if (text == null || text.isBlank()) {
            throw new InvalidTransactionException("MISSING_TIMESTAMP", "Settlement timestamp is required");
        }
        try {
            return OffsetDateTime.parse(text.trim()).toInstant().truncatedTo(ChronoUnit.MILLIS);
        } catch (DateTimeParseException e) {
            throw new InvalidTransactionException("INVALID_TIMESTAMP",
                    "Timestamp must be ISO-8601 with offset, e.g. 2026-03-10T15:00:00Z: '" + truncate(text.trim(), 40) + "'");
        }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
