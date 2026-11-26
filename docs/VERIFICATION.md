🌐 [Versión en español](VERIFICATION.esp.md)

# Verification & Testing Guide (v1.1.0)

This guide covers how to run the automated test suites, start the service locally against SQL Server 2022, and validate end-to-end behavior using the included sample datasets.

## 0. Prerequisites

- **JDK 21** (Eclipse Temurin or equivalent)
- **Maven 3.9+**
- **Docker** (Docker Desktop / Docker Engine with WSL2 or Linux socket access, required for Testcontainers integration tests and local SQL Server)

## 1. Unit & Web Slice Tests (`mvn clean test`)

Runs all fast unit tests and Spring MVC web-slice tests via Maven Surefire without requiring Docker.

mvn clean test

- **Expected result:** `BUILD SUCCESS` with **209 tests run, 0 failures, 0 errors**.
- **Coverage:** Domain rules and value objects (`MoneyTest`, `ReconciliationRulesTest`, `AuditRulesTest`, `SettlementWindowTest`), application pipeline orchestration (`BatchPipelineTest`, `ReconciliationScopeTest`, `RecoveryAndFailureTest`, `SampleFilesTest`, `SyntheticDataPipelineTest`), streaming file readers (`FileReadersTest`), async dispatching (`AsyncBatchDispatcherTest`), and REST/security controllers (`BatchControllerTest`, `ApiKeyFilterTest`, `SeedControllerDisabledInProdTest`).

## 2. SQL Server Integration Tests (`mvn verify`)

Runs the full lifecycle including Failsafe integration tests (`*IT.java`). Testcontainers (`1.21.4`) automatically pulls and starts a real `mcr.microsoft.com/mssql/server:2022-latest` container, applies all 9 Flyway migrations, and runs database-level tests.

mvn verify

- **Expected result:** `BUILD SUCCESS` with **35 integration tests run, 0 failures, 0 errors, 0 skipped**:
  - `MigrationIT` — Verifies Flyway `V1`–`V9`, `DECIMAL(19,4)` money columns, filtered unique indexes, and `CHECK` constraints.
  - `ReconciliationFlowIT` — End-to-end ingestion, matching, discrepancy persistence, and audit alert deduplication.
  - `ScopeAndCrossBatchIT` — Validates declared/inferred windows, due cutoffs, deferred ledger entries, late settlements, and late bookings.
  - `ConcurrencyAndRecoveryIT` — Tests atomic batch claiming, single-active-batch-per-source indexing, lease fencing (`attempt_no`), bounded purge transactions, and crash recovery.
  - `ApiKeySecurityIT` — Verifies constant-time `X-API-Key` enforcement and `prod` profile startup guards.
  - `TimeZoneIT` — Confirms UTC timestamps round-trip identically between JPA entities and raw JDBC even when the host OS is in a non-UTC offset (e.g., `America/Lima`).

> **Note:** If Docker is not running, Testcontainers skips `*IT.java` tests rather than failing. Always ensure `Skipped: 0` when validating database changes.

## 3. Local Startup & Security Checks

### Starting the service in `dev` mode

cp .env.example .env
docker compose up -d

# Load environment variables (Linux/macOS/Git Bash)
set -a
source .env
set +a

SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run

*(On Windows PowerShell, set the variables from `.env` in your session or IDE run configuration before running `mvn spring-boot:run`.)*

- **Expected startup logs:** `Successfully applied 9 migrations` (Flyway) followed by `Started ReconciliationApplication`.
- **Health check:** `curl http://localhost:8080/actuator/health` returns `{"status":"UP"}`.
- **Swagger UI:** Available at `http://localhost:8080/swagger-ui.html`.

### Production safety checks

- Starting with `SPRING_PROFILES_ACTIVE=prod` and an empty or `<16`-character `API_KEY` **aborts startup immediately** with a clear configuration error.
- Starting with no active profile (`SPRING_PROFILES_ACTIVE` unset) leaves `SeedController` unregistered (`POST /api/v1/ledger/seed` returns `404 Not Found`).

## 4. Automated Endpoint Smoke Test (`scripts/smoke-test.sh`)

Exercises all 5 API endpoints against a running server (`curl` and `jq` required):

API_URL=http://localhost:8080 API_KEY=<your-api-key> ./scripts/smoke-test.sh

The script seeds 400 synthetic ledger entries, polls the batch until `COMPLETED`, asserts every summary metric against the seed's expected values, tests discrepancy pagination/filtering, and verifies idempotent CSV uploads (`202` followed by `200`).

- **Expected output:** Ends with `ALL CHECKS PASSED` (exit code `0`).

## 5. Manual Functional Verification with Sample Files

To verify multi-day cross-batch reconciliation manually against your local SQL Server instance:

### 5.1. Load the Day-1 ledger and upload the Niubiz sample

sqlcmd -S localhost,1433 -U reconciliation_app -P "$DB_PASSWORD" -C -d ReconciliationDB -i samples/sample_ledger.sql

curl -H "X-API-Key: $API_KEY" \
  -F "file=@samples/settlements_niubiz_sample.csv" \
  -F "sourceName=NIUBIZ" \
  http://localhost:8080/api/v1/batches/upload

curl -H "X-API-Key: $API_KEY" \
  http://localhost:8080/api/v1/batches/1/summary

**Expected Day-1 summary:**

- `totalRecords: 14`
- `validRecords: 11`
- `invalidRecords: 3`
- `evaluatedReferences: 12`
- `matchedTransactions: 6`
- `discrepancyCount: 6`
- `reconciliationRatePercent: 50.00`
- `totalDiscrepancyAmount.PEN: 865.0000`
- `deferredLedgerEntries: 2`
- `alertsGenerated: 3` (`HIGH: 2`, `MEDIUM: 1`)
- `dueCutoff: "2026-03-09T16:01:00Z"`

### 5.2. Load the late-booking ledger entry and upload Day 2

sqlcmd -S localhost,1433 -U reconciliation_app -P "$DB_PASSWORD" -C -d ReconciliationDB -i samples/sample_ledger_late_booking.sql

curl -H "X-API-Key: $API_KEY" \
  -F "file=@samples/settlements_niubiz_sample_day2.csv" \
  -F "sourceName=NIUBIZ" \
  http://localhost:8080/api/v1/batches/upload

**Expected Day-2 result:** Batch 2 reports `matchedTransactions: 4, discrepancyCount: 0`, and querying Batch 1's summary now shows `resolvedByLaterBatches: 2, openDiscrepancies: 4`.

### 5.3. Upload the Izipay JSON sample (`sourceName=IZIPAY`)

- Reports `evaluatedReferences: 4`, `matchedTransactions: 2`, and `totalDiscrepancyAmount.PEN: 65.5000`.

*(You can also run `SAMPLES=1 ./scripts/smoke-test.sh` to automate the Day-1 sample checks; these exact assertions are continuously verified by `SampleFilesTest`.)*

## 6. Verified Baseline (v1.1.0)

| Verification Layer | Command / Tool | Status |
|---|---|---|
| Compilation (`main` + `test`) | `mvn test-compile` (JDK 21) | **Passed (0 errors)** |
| Unit & Web Slice Suite | `mvn test` (Maven Surefire) | **Passed (209 / 209)** |
| SQL Server 2022 Integration Suite | `mvn verify` (Maven Failsafe + Testcontainers 1.21.4) | **Passed (35 / 35)** |
| Schema Migrations (`V1`–`V9`) | Flyway on SQL Server 2022 (`MigrationIT`) | **Passed** |
| Timezone Consistency (`UTC` & `America/Lima`) | `TimeZoneIT` | **Passed** |