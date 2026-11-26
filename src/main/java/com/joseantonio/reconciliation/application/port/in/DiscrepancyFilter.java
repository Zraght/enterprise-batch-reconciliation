package com.joseantonio.reconciliation.application.port.in;

import com.joseantonio.reconciliation.domain.exception.InvalidRequestException;
import com.joseantonio.reconciliation.domain.model.MatchStatus;

import java.util.Locale;

/**
 * Optional filters over the discrepancies of a batch. {@code MATCHED} is not a discrepancy and is rejected.
 *
 * @param referenceContains case-insensitive substring of the reference code
 * @param currency          ISO-4217 code of the discrepancy currency
 * @param resolved          {@code true} = only those closed by a later batch, {@code false} = only the still open ones,
 *                          {@code null} = both
 */
public record DiscrepancyFilter(MatchStatus matchStatus, String referenceContains, String currency, Boolean resolved) {

    public DiscrepancyFilter {
        if (matchStatus == MatchStatus.MATCHED) {
            throw new InvalidRequestException("INVALID_FILTER", "MATCHED is not a discrepancy status");
        }
        referenceContains = referenceContains == null || referenceContains.isBlank()
                ? null : referenceContains.trim().toUpperCase(Locale.ROOT);
        currency = currency == null || currency.isBlank() ? null : currency.trim().toUpperCase(Locale.ROOT);
        if (referenceContains != null && referenceContains.length() > 64) {
            throw new InvalidRequestException("INVALID_FILTER", "reference filter is too long");
        }
        if (currency != null && !currency.matches("[A-Z]{3}")) {
            throw new InvalidRequestException("INVALID_FILTER", "currency must be a 3-letter ISO code");
        }
    }

    public DiscrepancyFilter(MatchStatus matchStatus, String referenceContains, String currency) {
        this(matchStatus, referenceContains, currency, null);
    }

    public static DiscrepancyFilter none() {
        return new DiscrepancyFilter(null, null, null, null);
    }
}
