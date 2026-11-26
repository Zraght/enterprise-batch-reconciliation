package com.joseantonio.reconciliation.domain.rule;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Currency;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Operating window and "high value" thresholds. The window is {@code [opensAt, closesAt)} in {@code zone};
 * if {@code opensAt > closesAt} the window crosses midnight; if both are equal the bank operates 24h.
 */
public record OperatingHoursPolicy(
        ZoneId zone,
        LocalTime opensAt,
        LocalTime closesAt,
        Map<Currency, BigDecimal> highValueThresholds) {

    public OperatingHoursPolicy {
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(opensAt, "opensAt");
        Objects.requireNonNull(closesAt, "closesAt");
        highValueThresholds = highValueThresholds == null ? Map.of() : Map.copyOf(highValueThresholds);
    }

    public boolean isOutsideOperatingHours(Instant instant) {
        if (opensAt.equals(closesAt)) {
            return false;
        }
        LocalTime t = instant.atZone(zone).toLocalTime();
        if (opensAt.isBefore(closesAt)) {
            return t.isBefore(opensAt) || !t.isBefore(closesAt);
        }
        return t.isBefore(opensAt) && !t.isBefore(closesAt);
    }

    public Optional<BigDecimal> highValueThreshold(Currency currency) {
        return Optional.ofNullable(highValueThresholds.get(currency));
    }
}
