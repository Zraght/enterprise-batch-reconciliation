package com.joseantonio.reconciliation.infrastructure.persistence;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base class of the integration tests: a REAL SQL Server in Docker, real Flyway migrations, real JDBC/JPA adapters.
 * Skipped automatically when Docker is not available. Executed by Maven Failsafe ({@code mvn verify}).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
abstract class AbstractSqlServerIT {

    /**
     * JVM-wide singleton container. It is created lazily, the first time a Spring context needs the datasource
     * properties (that is, only when Docker is available and a test actually runs), and reused by every IT class;
     * Testcontainers' Ryuk sidecar removes it when the JVM exits.
     */
    private static final class Database {
        static final MSSQLServerContainer<?> SQL_SERVER =
                new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest")
                        .acceptLicense()
                        .withUrlParam("trustServerCertificate", "true");

        static {
            SQL_SERVER.start();
        }
    }

    private static final AtomicInteger SOURCE_SEQ = new AtomicInteger();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws IOException {
        registry.add("spring.datasource.url", Database.SQL_SERVER::getJdbcUrl);
        registry.add("spring.datasource.username", Database.SQL_SERVER::getUsername);
        registry.add("spring.datasource.password", Database.SQL_SERVER::getPassword);
        Path storage = Files.createTempDirectory("reconciliation-it-");
        registry.add("reconciliation.storage.directory", storage::toString);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    /** Every test uses its own source so tests never see each other's ledger entries. */
    protected static String uniqueSource(String prefix) {
        return prefix + "-" + SOURCE_SEQ.incrementAndGet() + "-" + System.nanoTime();
    }

    protected void insertLedger(String source, String reference, String account, String amount, String currency,
                                String utcTimestamp) {
        insertLedger(source, reference, account, amount, currency, utcTimestamp, "POSTED");
    }

    protected void insertLedger(String source, String reference, String account, String amount, String currency,
                                String utcTimestamp, String status) {
        jdbc.update("INSERT INTO internal_ledger (reference_code, account_number, amount, currency, tx_timestamp, "
                        + "status, source_name, account_type, pre_authorized) VALUES (?, ?, ?, ?, ?, ?, ?, 'RETAIL', 0)",
                reference, account, new java.math.BigDecimal(amount), currency,
                java.time.LocalDateTime.parse(utcTimestamp), status, source);
    }
}
