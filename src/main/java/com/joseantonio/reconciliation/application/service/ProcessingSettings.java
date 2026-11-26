package com.joseantonio.reconciliation.application.service;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Tunables of the processing pipeline.
 *
 * @param chunkSize        records per transaction in every phase
 * @param maxInvalidRatio  a file whose invalid/total ratio exceeds this is rejected as garbage (0..1)
 * @param staleAfter       a PROCESSING batch without heartbeat for this long is considered crashed
 * @param recoveryLimit    max pending batches re-dispatched per recovery run
 */
public record ProcessingSettings(int chunkSize, BigDecimal maxInvalidRatio, Duration staleAfter, int recoveryLimit) {

    public ProcessingSettings {
        if (chunkSize < 1) {
            throw new IllegalArgumentException("chunkSize must be >= 1");
        }
        if (maxInvalidRatio == null || maxInvalidRatio.signum() < 0 || maxInvalidRatio.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("maxInvalidRatio must be within [0,1]");
        }
        if (staleAfter == null || staleAfter.isNegative() || staleAfter.isZero()) {
            throw new IllegalArgumentException("staleAfter must be positive");
        }
        if (recoveryLimit < 1) {
            throw new IllegalArgumentException("recoveryLimit must be >= 1");
        }
    }

    public static ProcessingSettings defaults() {
        return new ProcessingSettings(1000, new BigDecimal("0.5"), Duration.ofMinutes(5), 100);
    }
}
