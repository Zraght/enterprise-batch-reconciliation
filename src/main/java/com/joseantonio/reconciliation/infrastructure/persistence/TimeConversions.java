package com.joseantonio.reconciliation.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** All DATETIME2 columns hold UTC. Domain code works with {@link Instant}; the database with UTC LocalDateTime. */
public final class TimeConversions {

    private TimeConversions() {
    }

    public static LocalDateTime toUtc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public static Instant toInstant(LocalDateTime utc) {
        return utc == null ? null : utc.toInstant(ZoneOffset.UTC);
    }
}
