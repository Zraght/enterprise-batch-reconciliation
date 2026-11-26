package com.joseantonio.reconciliation.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The settlement period a file covers, and the reconciliation scope that follows from it.
 *
 * <p>Ledger movements are booked at {@code tx_timestamp}, but a gateway settles them some time later (the
 * <em>settlement lag</em>). A movement can therefore only be called "missing from the file" once the file's period has
 * advanced far enough past its booking time that it should already have settled:</p>
 * <pre>
 *     due cutoff = period end - settlement lag
 *     movement booked at t is DUE         when t &lt;= due cutoff  -> absent from the file = MISSING_EXTERNAL
 *     movement booked at t is DEFERRED    when t &gt;  due cutoff  -> pending settlement, not reported, stays eligible
 * </pre>
 * Pairing a settlement with its ledger movement is independent of this window: a settlement that shows up early or late
 * still reconciles; the window only decides which unpaired movements are overdue.
 *
 * @param source whether the period was declared by the uploader or inferred from the file content
 */
public record SettlementWindow(Instant start, Instant end, Source source) {

    public enum Source { DECLARED, INFERRED }

    public SettlementWindow {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(source, "source");
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("Settlement period ends before it starts: " + start + " > " + end);
        }
    }

    public static SettlementWindow declared(Instant start, Instant end) {
        return new SettlementWindow(start, end, Source.DECLARED);
    }

    /** Period inferred from the earliest and latest settlement timestamp of the valid records of a file. */
    public static SettlementWindow inferred(Instant earliest, Instant latest) {
        return new SettlementWindow(earliest, latest, Source.INFERRED);
    }

    public Instant dueCutoff(Duration settlementLag) {
        if (settlementLag.isNegative()) {
            throw new IllegalArgumentException("Settlement lag cannot be negative");
        }
        return end.minus(settlementLag);
    }

    public boolean isDue(Instant bookedAt, Duration settlementLag) {
        return !bookedAt.isAfter(dueCutoff(settlementLag));
    }
}
