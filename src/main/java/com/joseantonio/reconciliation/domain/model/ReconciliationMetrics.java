package com.joseantonio.reconciliation.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Pure financial/statistical calculations shared by the reporting use cases. */
public final class ReconciliationMetrics {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private ReconciliationMetrics() {
    }

    /**
     * Reconciliation rate as a percentage with 2 decimals (banker's rounding): matched / totalEvaluated * 100.
     * Returns 0.00 when nothing was evaluated.
     */
    public static BigDecimal reconciliationRate(long matched, long totalEvaluated) {
        if (matched < 0 || totalEvaluated < 0 || matched > totalEvaluated) {
            throw new IllegalArgumentException("Invalid counts: matched=" + matched + ", total=" + totalEvaluated);
        }
        if (totalEvaluated == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return BigDecimal.valueOf(matched)
                .multiply(HUNDRED)
                .divide(BigDecimal.valueOf(totalEvaluated), 2, RoundingMode.HALF_EVEN);
    }
}
