package com.joseantonio.reconciliation.domain;

import com.joseantonio.reconciliation.domain.exception.InvalidTransactionException;
import com.joseantonio.reconciliation.domain.model.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoneyTest {

    @Test
    void normalisesToScaleFour() {
        Money m = Money.of("100.5", "PEN");
        assertEquals(4, m.amount().scale());
        assertEquals("100.5000", m.amount().toPlainString());
    }

    @Test
    void trailingZerosBeyondScaleAreAccepted() {
        assertEquals("10.2500", Money.of("10.250000", "USD").amount().toPlainString());
    }

    @Test
    void scientificNotationIsSupported() {
        assertEquals("1500.0000", Money.of("1.5E3", "PEN").amount().toPlainString());
    }

    @Test
    void neverUsesBinaryFloatingPoint() {
        // 0.1 + 0.2 must be exactly 0.3 with BigDecimal semantics
        BigDecimal sum = Money.of("0.1", "PEN").amount().add(Money.of("0.2", "PEN").amount());
        assertEquals(0, sum.compareTo(new BigDecimal("0.3")));
    }

    @ParameterizedTest
    @CsvSource({
            "0, INVALID_AMOUNT",
            "-1.00, INVALID_AMOUNT",
            "abc, INVALID_AMOUNT",
            "'1,000.00', INVALID_AMOUNT",
            "10.12345, AMOUNT_SCALE_EXCEEDED",
            "1000000000000000, AMOUNT_OUT_OF_RANGE",
            "1E+999999999, AMOUNT_OUT_OF_RANGE"
    })
    void rejectsInvalidAmounts(String text, String expectedCode) {
        InvalidTransactionException ex =
                assertThrows(InvalidTransactionException.class, () -> Money.of(text, "PEN"));
        assertEquals(expectedCode, ex.code());
    }

    @Test
    void acceptsMaximumDecimal19_4() {
        assertEquals("999999999999999.9999", Money.of("999999999999999.9999", "PEN").amount().toPlainString());
    }

    @Test
    void rejectsBlankAmountAndCurrency() {
        assertEquals("MISSING_AMOUNT", assertThrows(InvalidTransactionException.class, () -> Money.of("  ", "PEN")).code());
        assertEquals("MISSING_CURRENCY", assertThrows(InvalidTransactionException.class, () -> Money.of("1", " ")).code());
    }

    @ParameterizedTest
    @ValueSource(strings = {"XYZ", "PE", "PENN", "12A"})
    void rejectsNonIsoCurrencies(String code) {
        assertEquals("INVALID_CURRENCY", assertThrows(InvalidTransactionException.class, () -> Money.of("1", code)).code());
    }

    @Test
    void currencyIsCaseInsensitive() {
        assertEquals("PEN", Money.of("1", " pen ").currencyCode());
    }

    @Test
    void comparisonHelpers() {
        Money a = Money.of("10", "PEN");
        assertTrue(a.sameValue(Money.of("10.0000", "PEN")));
        assertFalse(a.sameValue(Money.of("10", "USD")));
        assertTrue(a.sameAmount(Money.of("10", "USD")));
        assertFalse(a.sameAmount(Money.of("10.0001", "PEN")));
    }
}
