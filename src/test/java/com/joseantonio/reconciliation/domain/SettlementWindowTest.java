package com.joseantonio.reconciliation.domain;

import com.joseantonio.reconciliation.domain.exception.InvalidRequestException;
import com.joseantonio.reconciliation.domain.model.SettlementWindow;
import com.joseantonio.reconciliation.domain.model.SourceName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementWindowTest {

    private static final Instant START = Instant.parse("2026-03-10T00:00:00Z");
    private static final Instant END = Instant.parse("2026-03-10T23:59:59Z");
    private final SettlementWindow window = SettlementWindow.declared(START, END);

    @Test
    void dueCutoffIsPeriodEndMinusTheLag() {
        assertEquals(END.minus(Duration.ofDays(1)), window.dueCutoff(Duration.ofDays(1)));
        assertEquals(END, window.dueCutoff(Duration.ZERO));
    }

    @Test
    void aMovementIsDueUpToAndIncludingTheCutoff() {
        Duration lag = Duration.ofHours(24);
        Instant cutoff = window.dueCutoff(lag);
        assertTrue(window.isDue(cutoff, lag));
        assertTrue(window.isDue(cutoff.minusMillis(1), lag));
        assertFalse(window.isDue(cutoff.plusMillis(1), lag));
    }

    @Test
    void sourceIsRecorded() {
        assertEquals(SettlementWindow.Source.DECLARED, window.source());
        assertEquals(SettlementWindow.Source.INFERRED, SettlementWindow.inferred(START, END).source());
    }

    @Test
    void invalidWindowsAndLagsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> SettlementWindow.declared(END, START));
        assertThrows(IllegalArgumentException.class, () -> window.dueCutoff(Duration.ofSeconds(-1)));
        assertEquals(START, SettlementWindow.inferred(START, START).end().minusSeconds(0).minus(Duration.ZERO));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "-starts-with-dash", "bad\nname", "<script>", "semi;colon", "ñandú"})
    void sourceNamesAreRestrictedToIdentifierLikeText(String raw) {
        assertEquals("INVALID_SOURCE", assertThrows(InvalidRequestException.class, () -> SourceName.require(raw)).code());
    }

    @Test
    void validSourceNamesAreTrimmed() {
        assertEquals("Niubiz-PE_1.0 prod", SourceName.require("  Niubiz-PE_1.0 prod "));
        assertEquals("A".repeat(100), SourceName.require("A".repeat(100)));
        assertThrows(InvalidRequestException.class, () -> SourceName.require("A".repeat(101)));
        assertThrows(InvalidRequestException.class, () -> SourceName.require(null));
    }
}
