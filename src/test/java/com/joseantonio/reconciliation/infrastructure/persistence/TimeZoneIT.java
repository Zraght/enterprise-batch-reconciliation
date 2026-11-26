package com.joseantonio.reconciliation.infrastructure.persistence;

import com.joseantonio.reconciliation.application.port.out.BatchRepositoryPort;
import com.joseantonio.reconciliation.application.port.out.ClaimedBatch;
import com.joseantonio.reconciliation.application.port.out.FileStoragePort;
import com.joseantonio.reconciliation.application.port.out.NewBatch;
import com.joseantonio.reconciliation.application.port.out.StoredFile;
import com.joseantonio.reconciliation.domain.model.BatchSnapshot;
import com.joseantonio.reconciliation.domain.model.FileFormat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression test. Every DATETIME2 column holds UTC wall-clock time, written and read both through JPA (the batch entity)
 * and through plain JDBC/SQL (lifecycle statements). With {@code hibernate.jdbc.time_zone=UTC} the two paths disagreed by
 * the JVM's UTC offset whenever the JVM was not running in UTC (for example America/Lima, UTC-5): declared settlement
 * periods and the due cutoff came back five hours off. The test simulates such a host by switching the JVM zone after the
 * application started (the application itself pins UTC at start-up; the data access must also be correct on its own).
 */
class TimeZoneIT extends AbstractSqlServerIT {

    @Autowired BatchRepositoryPort batches;
    @Autowired FileStoragePort storage;

    @Test
    void utcColumnsRoundTripIdenticallyThroughJpaAndJdbcWhenTheJvmIsNotInUtc() {
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("America/Lima")); // UTC-5, no DST
        try {
            Instant start = Instant.parse("2026-03-10T00:00:00Z");
            Instant end = Instant.parse("2026-03-10T23:59:59Z");
            Instant executionDate = Instant.parse("2026-03-10T12:00:00Z");
            String source = uniqueSource("TZ");
            StoredFile file = storage.store(new ByteArrayInputStream(
                    "external_ref,amount,currency,settlement_timestamp\nA,1.00,PEN,2026-03-10T15:00:00Z\n"
                            .getBytes(StandardCharsets.UTF_8)), "tz.csv");

            // Written through JPA ...
            long id = batches.register(new NewBatch(source, "tz.csv", FileFormat.CSV, file.sha256(), file.sizeBytes(),
                    file.location(), executionDate, start, end)).batch().batchId();

            // ... the column holds the UTC wall-clock value (checked with plain JDBC, which never shifts).
            assertEquals(LocalDateTime.parse("2026-03-10T00:00:00"), jdbc.queryForObject(
                    "SELECT declared_period_start FROM reconciliation_batches WHERE batch_id = ?", LocalDateTime.class, id));
            assertEquals(LocalDateTime.parse("2026-03-10T23:59:59"), jdbc.queryForObject(
                    "SELECT declared_period_end FROM reconciliation_batches WHERE batch_id = ?", LocalDateTime.class, id));
            assertEquals(LocalDateTime.parse("2026-03-10T12:00:00"), jdbc.queryForObject(
                    "SELECT execution_date FROM reconciliation_batches WHERE batch_id = ?", LocalDateTime.class, id));

            // Written through SQL (UTC) ... read through JPA comes back as the very same instant.
            jdbc.update("UPDATE reconciliation_batches SET due_cutoff = '2026-03-09T16:01:00' WHERE batch_id = ?", id);
            BatchSnapshot snapshot = batches.findById(id).orElseThrow();
            assertEquals(Instant.parse("2026-03-09T16:01:00Z"), snapshot.dueCutoff());
            assertEquals(start, snapshot.declaredPeriodStart());
            assertEquals(end, snapshot.declaredPeriodEnd());
            assertEquals(executionDate, snapshot.executionDate());

            // The JDBC claim returns the declared period untouched, so the reconciliation scope is the one the user declared.
            ClaimedBatch claimed = batches.tryClaim(id).orElseThrow();
            assertEquals(start, claimed.declaredPeriodStart());
            assertEquals(end, claimed.declaredPeriodEnd());
            batches.markFailed(claimed, "TimeZoneIT finished"); // leave no PROCESSING batch behind
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
