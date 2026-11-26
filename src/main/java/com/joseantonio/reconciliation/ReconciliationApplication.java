package com.joseantonio.reconciliation;

import com.joseantonio.reconciliation.infrastructure.config.ReconciliationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.TimeZone;

@SpringBootApplication
@EnableConfigurationProperties(ReconciliationProperties.class)
public class ReconciliationApplication {

    static {
        // Every DATETIME2 column holds UTC wall-clock time. Hibernate converts java.time.LocalDateTime through
        // java.sql.Timestamp, which is interpreted in the JVM's default zone: pinning the zone to UTC makes that
        // conversion the identity on any host (and removes the DST-gap edge case of Timestamp.valueOf). Business rules
        // that need a local zone (operating hours) use an explicit ZoneId and are unaffected.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    public static void main(String[] args) {
        SpringApplication.run(ReconciliationApplication.class, args);
    }
}
