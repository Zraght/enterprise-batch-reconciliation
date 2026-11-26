🌐 [Versión en español](PROJECT_STATUS.esp.md)

# Project Status — v1.1.0

Current engineering and verification state of **Enterprise Batch Reconciliation & Audit Engine**. Everything listed below has been compiled and executed locally against JDK 21 and a live SQL Server 2022 container.

---

## Verification Summary

| Suite | Runner | Target | Result |
|---|---|---|---|
| Unit & Web Slice (`*Test.java`) | Maven Surefire | Domain rules, application pipeline, streaming readers, REST controllers, API key security | **209 / 209 passed** |
| Integration (`*IT.java`) | Maven Failsafe + Testcontainers 1.21.4 | Real SQL Server 2022 (`mcr.microsoft.com/mssql/server:2022-latest`) + Flyway `V1`–`V9` | **35 / 35 passed** |
| **Total** | `mvn verify` | Full build & test lifecycle | **244 / 244 passed (`BUILD SUCCESS`)** |

---

## Implemented & Verified Capabilities

### 1. Concurrency, Fencing & Crash Recovery
- Fenced `purgeDerivedData` runs in bounded transactions (`<= 2,000` rows per delete) guarded by `BatchOwnershipGuard`, ensuring stale workers cannot modify or delete rows after losing a lease.
- Failed batches release claimed ledger entries and reopen previously resolved discrepancies.
- Verified in-memory (`RecoveryAndFailureTest`) and against SQL Server row locks (`ConcurrencyAndRecoveryIT`).

### 2. Reconciliation Scope & Cross-Batch Resolution
- Enforces declared or inferred settlement periods, configurable settlement lag (`P1D`), due cutoffs, deferred movements, and `PENDING` / `REVERSED` ledger filtering.
- Handles repeated external references (classifying identical vs. conflicting duplicates) and cross-batch resolution for late settlements and late bookings (`ALREADY_RECONCILED`).
- Verified in `ReconciliationScopeTest`, `SampleFilesTest`, and `ScopeAndCrossBatchIT`.

### 3. Historical Audit & Anomaly Detection
- Evaluates cross-batch and cross-source history to detect temporal duplicates, corporate threshold breaches, and off-hours activity (`OperatingHoursPolicy`).
- Enforces deterministic alert identity (`one alert per movement + rule`) at both domain and database constraint levels (`V9` migration).
- Verified in `AuditRulesTest`, `AuditHistoryTest`, and `ReconciliationFlowIT`.

### 4. Schema Integrity & Timezone Consistency
- 9 versioned Flyway migrations (`V1`–`V9`) covering batches, internal ledger uniqueness (`POSTED` scope), external settlements, reconciliation results, audit alerts, invalid record quarantine, and cross-batch resolution columns.
- JVM default timezone is pinned to UTC at startup (`ReconciliationApplication`) so JPA and raw JDBC timestamps remain consistent regardless of host timezone (tested under `America/Lima` and `UTC`).
- Verified by `MigrationIT` and `TimeZoneIT`.

### 5. API Security & Operational Hardening
- Constant-time `X-API-Key` verification (`ApiKeyVerifier`, `ApiKeyFilter`) with deny-by-default semantics, path normalization, and mandatory key enforcement in `prod`.
- Synthetic seeding endpoint (`SeedController`) is strictly disabled under the `prod` profile.
- File readers sanitize error messages so internal filesystem paths are never leaked in failure reasons or API responses.
- Verified by `ApiKeyFilterTest`, `SeedControllerDisabledInProdTest`, `SeedControllerEnabledTest`, and `ApiKeySecurityIT`.

---

## Environment & Build Notes

- **Runtime & Build:** Java 21 (Eclipse Temurin), Spring Boot 3.4.5, Maven 3.9.9.
- **Testcontainers Compatibility:** Pinned `<testcontainers.version>1.21.4</testcontainers.version>` in `pom.xml` to support modern Docker Engine API versions (Docker Desktop 4.100+ / Engine 29+ over WSL2/Windows npipe).

---

## Future Roadmap (Out of Scope for v1.1.0)

1. **Multi-user Authentication:** Replace shared-secret API keys with OAuth2 / OIDC at the API gateway level for role-based operator access.
2. **Distributed File Storage:** Add an S3 / Azure Blob implementation of `FileStoragePort` to support multi-node horizontal scaling without shared local disk.
3. **CI/CD Pipeline:** Add a GitHub Actions workflow running `mvn verify` on pull requests.
