package com.joseantonio.reconciliation.domain;

import com.joseantonio.reconciliation.domain.model.FileFormat;
import com.joseantonio.reconciliation.domain.model.ReconciliationMetrics;
import com.joseantonio.reconciliation.domain.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReconciliationMetricsTest {

    @Test
    void rateUsesBankersRoundingWithTwoDecimals() {
        assertEquals(new BigDecimal("66.67"), ReconciliationMetrics.reconciliationRate(2, 3));
        assertEquals(new BigDecimal("100.00"), ReconciliationMetrics.reconciliationRate(5, 5));
        assertEquals(new BigDecimal("0.12"), ReconciliationMetrics.reconciliationRate(1, 800)); // 0.125 -> HALF_EVEN -> 0.12
    }

    @Test
    void rateOfNothingIsZero() {
        assertEquals(new BigDecimal("0.00"), ReconciliationMetrics.reconciliationRate(0, 0));
    }

    @Test
    void rateRejectsInconsistentCounts() {
        assertThrows(IllegalArgumentException.class, () -> ReconciliationMetrics.reconciliationRate(3, 2));
        assertThrows(IllegalArgumentException.class, () -> ReconciliationMetrics.reconciliationRate(-1, 2));
    }

    @Test
    void fileFormatIsDetectedFromExtension() {
        assertEquals(FileFormat.CSV, FileFormat.fromFileName("Settlements.CSV"));
        assertEquals(FileFormat.JSON, FileFormat.fromFileName("a.b.json"));
        assertThrows(InvalidRequestException.class, () -> FileFormat.fromFileName("x.xml"));
        assertThrows(InvalidRequestException.class, () -> FileFormat.fromFileName(" "));
    }
}
