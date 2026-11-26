package com.joseantonio.reconciliation.domain;

import com.joseantonio.reconciliation.domain.exception.InvalidTransactionException;
import com.joseantonio.reconciliation.domain.model.ReferenceCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReferenceCodeTest {

    @Test
    void normalisesCaseAndWhitespace() {
        assertEquals(ReferenceCode.of("TX-001/a"), ReferenceCode.of("  tx-001/A "));
        assertEquals("TX-001/A", ReferenceCode.of("tx-001/a").value());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "-LEADING", "has space", "bad*char", "ñandú"})
    void rejectsInvalid(String value) {
        assertThrows(InvalidTransactionException.class, () -> ReferenceCode.of(value));
    }

    @Test
    void rejectsNull() {
        assertEquals("MISSING_REFERENCE", assertThrows(InvalidTransactionException.class, () -> ReferenceCode.of(null)).code());
    }

    @Test
    void rejectsTooLong() {
        assertThrows(InvalidTransactionException.class, () -> ReferenceCode.of("A".repeat(65)));
        ReferenceCode.of("A".repeat(64));
    }
}
