package com.joseantonio.reconciliation.domain.model;

import com.joseantonio.reconciliation.domain.exception.InvalidTransactionException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;

/**
 * Monetary amount. Financial policy of the whole system:
 * <ul>
 *   <li>{@link BigDecimal} only - never float/double.</li>
 *   <li>Scale 4, matching SQL Server {@code DECIMAL(19,4)} (max 15 integer digits).</li>
 *   <li>Amounts must be strictly positive.</li>
 *   <li>Inputs with more than 4 significant decimals are REJECTED, never silently rounded:
 *       rounding third-party financial data would hide real discrepancies.
 *       {@link #ROUNDING_MODE} (banker's rounding) is the policy for any derived calculation
 *       (e.g. percentages) and is applied here only as a no-op safeguard.</li>
 * </ul>
 */
public record Money(BigDecimal amount, Currency currency) {

    public static final int SCALE = 4;
    public static final int MAX_INTEGER_DIGITS = 15;
    public static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_EVEN;
    private static final int MAX_TEXT_LENGTH = 64;

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (amount.signum() <= 0) {
            throw new InvalidTransactionException("INVALID_AMOUNT",
                    "Amount must be greater than zero: " + amount.toPlainString());
        }
        // Range check BEFORE any scale operation: protects against inputs such as 1E+999999999.
        if (amount.precision() - amount.scale() > MAX_INTEGER_DIGITS) {
            throw new InvalidTransactionException("AMOUNT_OUT_OF_RANGE",
                    "Amount exceeds " + MAX_INTEGER_DIGITS + " integer digits (DECIMAL(19,4))");
        }
        if (amount.stripTrailingZeros().scale() > SCALE) {
            throw new InvalidTransactionException("AMOUNT_SCALE_EXCEEDED",
                    "Amount has more than " + SCALE + " decimal places: " + amount.toPlainString());
        }
        amount = amount.setScale(SCALE, ROUNDING_MODE);
    }

    public static Money of(String amountText, String currencyCode) {
        return new Money(parseAmount(amountText), parseCurrency(currencyCode));
    }

    public static Money of(BigDecimal amount, String currencyCode) {
        return new Money(amount, parseCurrency(currencyCode));
    }

    public static BigDecimal parseAmount(String text) {
        if (text == null || text.isBlank()) {
            throw new InvalidTransactionException("MISSING_AMOUNT", "Amount is required");
        }
        String trimmed = text.trim();
        if (trimmed.length() > MAX_TEXT_LENGTH) {
            throw new InvalidTransactionException("INVALID_AMOUNT", "Amount text is too long");
        }
        try {
            return new BigDecimal(trimmed);
        } catch (NumberFormatException e) {
            throw new InvalidTransactionException("INVALID_AMOUNT", "Amount is not a valid decimal number: '" + trimmed + "'");
        }
    }

    public static Currency parseCurrency(String code) {
        if (code == null || code.isBlank()) {
            throw new InvalidTransactionException("MISSING_CURRENCY", "Currency is required");
        }
        String normalised = code.trim().toUpperCase(Locale.ROOT);
        try {
            return Currency.getInstance(normalised);
        } catch (IllegalArgumentException e) {
            throw new InvalidTransactionException("INVALID_CURRENCY", "Not an ISO-4217 currency code: '" + normalised + "'");
        }
    }

    public boolean sameCurrency(Money other) {
        return currency.equals(other.currency);
    }

    /** Numeric equality of the amount, ignoring currency. */
    public boolean sameAmount(Money other) {
        return amount.compareTo(other.amount) == 0;
    }

    /** Same currency AND same numeric amount. */
    public boolean sameValue(Money other) {
        return sameCurrency(other) && sameAmount(other);
    }

    public String currencyCode() {
        return currency.getCurrencyCode();
    }
}
