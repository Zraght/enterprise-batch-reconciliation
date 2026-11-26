package com.joseantonio.reconciliation.domain.model;

import com.joseantonio.reconciliation.domain.exception.InvalidTransactionException;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Business reference shared by the internal ledger and the external settlements.
 * Normalised (trimmed, upper-case) so that both sides compare consistently.
 */
public record ReferenceCode(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Z0-9][A-Z0-9._\\-/]{0,63}");

    public ReferenceCode {
        if (value == null || value.isBlank()) {
            throw new InvalidTransactionException("MISSING_REFERENCE", "Reference code is required");
        }
        value = value.trim().toUpperCase(Locale.ROOT);
        if (!FORMAT.matcher(value).matches()) {
            throw new InvalidTransactionException("INVALID_REFERENCE",
                    "Reference code must be 1-64 characters of A-Z, 0-9, '.', '_', '-', '/' : '" + abbreviate(value) + "'");
        }
    }

    public static ReferenceCode of(String value) {
        return new ReferenceCode(value);
    }

    private static String abbreviate(String s) {
        return s.length() <= 80 ? s : s.substring(0, 80) + "...";
    }

    @Override
    public String toString() {
        return value;
    }
}
