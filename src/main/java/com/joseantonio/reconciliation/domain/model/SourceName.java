package com.joseantonio.reconciliation.domain.model;

import com.joseantonio.reconciliation.domain.exception.InvalidRequestException;

import java.util.regex.Pattern;

/**
 * Identifier of a gateway / clearing house. Deliberately restrictive (letters, digits, space, dot, underscore, hyphen):
 * it is a catalogue key, not free text, which also rules out control characters in log lines and headers.
 */
public final class SourceName {

    public static final int MAX_LENGTH = 100;
    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._\\-]{0,99}");

    private SourceName() {
    }

    /** @return the trimmed name, or throws {@link InvalidRequestException} */
    public static String require(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidRequestException("INVALID_SOURCE", "sourceName is required");
        }
        String name = raw.trim();
        if (!FORMAT.matcher(name).matches()) {
            throw new InvalidRequestException("INVALID_SOURCE",
                    "sourceName must be 1-" + MAX_LENGTH + " characters: letters, digits, space, '.', '_' or '-', "
                            + "starting with a letter or digit");
        }
        return name;
    }
}
