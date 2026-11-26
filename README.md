🌐 [Versión en español](README.esp.md)

# enterprise-batch-reconciliation

I built this because reconciling payment processor files (Niubiz, Izipay) against an internal ledger in spreadsheets breaks down the moment volume grows, settlements arrive a day late, or someone re-uploads the same file twice. I wanted a backend engine that takes raw settlement files, streams them without blowing up memory, matches them against SQL Server, and flags discrepancies and audit anomalies automatically.

It's a Spring Boot 3.4 / Java 21 REST service built with hexagonal architecture. You upload a CSV or JSON settlement file, it registers the batch with a SHA-256 hash (so duplicate uploads are caught immediately), processes the records asynchronously in chunks, and exposes endpoints to track progress and inspect discrepancies.

## Quick start

1. Start SQL Server 2022 and initialize the schema: `docker compose up -d`
2. Run the application: `mvn spring-boot:run`
3. Upload a sample Niubiz settlement file:

curl -X POST http://localhost:8080/api/v1/batches \
  -H "X-API-Key: local-dev-key" \
  -F "file=@samples/settlements_niubiz_sample.csv" \
  -F "source=NIUBIZ" \
  -F "periodStart=2026-03-01" \
  -F "periodEnd=2026-03-01"

## Getting started

Requires **Java 21**, **Maven 3.9+**, and **Docker** (for local SQL Server and integration tests).

Clone the repository:

git clone https://github.com/YOUR_USERNAME/enterprise-batch-reconciliation.git
cd enterprise-batch-reconciliation

Copy environment variables and start SQL Server:

cp .env.example .env
docker compose up -d

Once SQL Server is healthy, create the database using `database/01_create_database.sql`. Flyway handles all 9 schema migrations `V1`–`V9` automatically on startup:

mvn spring-boot:run

Swagger UI is available at `http://localhost:8080/swagger-ui.html`.

### Running the test suite

Unit and web tests run with Surefire:

mvn test

Integration tests (`*IT.java`) spin up a real SQL Server 2022 container via Testcontainers and run with Failsafe. Run all 244 tests (209 unit/web + 35 SQL Server integration tests):

mvn verify

There is also an end-to-end smoke script in `scripts/smoke-test.sh` if you want to test the running API against the sample files in `samples/`.

## API overview

All `/api/v1/**` endpoints require the `X-API-Key` header (configured via `.env`).

| Method | Endpoint | What it does |
| --- | --- | --- |
| `POST` | `/api/v1/batches` | Uploads a `.csv` or `.json` settlement file and queues async processing |
| `GET` | `/api/v1/batches/{id}` | Returns current batch status, phase (`INGESTING`, `RECONCILING`, `AUDITING`, `COMPLETED`), and counters |
| `GET` | `/api/v1/batches/{id}/summary` | Returns reconciliation metrics, matched totals, and audit alert counts |
| `GET` | `/api/v1/batches/{id}/discrepancies` | Paginated list of mismatches, orphans, and audit findings (filterable by status/severity) |
| `POST` | `/api/v1/seed` | Generates synthetic ledger and settlement datasets for local testing (disabled in `prod` profile) |

Full request/response payloads and error codes are documented in `docs/API.md`.

## What it actually does

### 1. Streaming ingestion & idempotency

Settlement files aren't loaded into memory all at once. `CsvRecordSource` and `JsonRecordSource` stream records in chunks while `CountingInputStream` calculates byte size and SHA-256 checksums on the fly. Malformed rows don't crash the batch — they get isolated into `batch_invalid_records` with the exact line and reason while valid rows keep moving.

### 2. Reconciliation rules

Each settlement record is evaluated against the internal ledger within the batch's declared `SettlementWindow`:

- **Exact match (`ExactMatchRule`):** Reference code, currency, and amount match.
- **Amount mismatch (`AmountMismatchRule`):** Reference exists in the ledger, but settled amount differs beyond configured tolerance.
- **Orphan detection (`OrphanDetectionRule`):** External settlement has no ledger entry, or an in-scope ledger entry was never settled.
- **Late bookings & cross-batch resolution:** Handles transactions booked after cutoff and resolves previously open discrepancies when a later batch settles them (`V9` migration).

### 3. Audit & anomaly checks

After matching, `AuditEngine` runs compliance and anomaly rules:

- **Temporal duplicates (`TemporalDuplicateRule`):** Flags repeated charges with matching characteristics inside a tight time window.
- **Corporate thresholds (`CorporateThresholdRule`):** Flags single transactions exceeding configured corporate limits.
- **Unusual operating hours (`UnusualTransactionTimeRule`):** Flags activity recorded outside normal business hours (`OperatingHoursPolicy`).

### 4. Crash recovery & concurrency

If the JVM stops mid-batch, `BatchRecoveryScheduler` detects stale locks on startup/schedule and resumes safely without double-counting, guarded by `BatchOwnershipGuard` at the database level.

## Configuration

Defaults live in `src/main/resources/application.yml`, with environment overrides in `application-dev.yml`, `application-prod.yml`, and `.env.example`:

| Variable / Property | Purpose |
| --- | --- |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | SQL Server JDBC connection |
| `API_KEY` | Expected `X-API-Key` header value |
| `reconciliation.processing.chunk-size` | Records processed per transaction chunk |
| `reconciliation.matching.amount-tolerance` | Allowed decimal variance for exact matching |
| `reconciliation.audit.corporate-threshold` | High-value transaction alert threshold |
| `reconciliation.operating-hours.*` | Start/end window for unusual-hour detection |

## Project structure

enterprise-batch-reconciliation/
├── pom.xml                           # Maven build (Java 21, Spring Boot 3.4.5, Testcontainers 1.21.4)
├── docker-compose.yml                # Local SQL Server 2022 container
├── database/
│   └── 01_create_database.sql        # Initial database creation script
├── docs/
│   ├── API.md                        # Endpoint contracts and examples
│   ├── ARCHITECTURE.md               # Hexagonal design, state machine, concurrency model
│   └── VERIFICATION.md               # Testing strategy and manual verification guide
├── samples/                          # Sample Niubiz (CSV), Izipay (JSON), and SQL ledger scripts
├── scripts/
│   └── smoke-test.sh                 # End-to-end curl smoke test
└── src/
    ├── main/
    │   ├── java/com/joseantonio/reconciliation/
    │   │   ├── domain/               # Pure models (Money, SettlementWindow) and rules (ReconciliationEngine, AuditEngine)
    │   │   ├── application/          # Use cases, inbound/outbound ports, batch orchestration services
    │   │   └── infrastructure/       # REST controllers, API key security, streaming readers, JPA/JDBC adapters
    │   └── resources/
    │       ├── application*.yml      # Profiles (default, dev, prod)
    │       └── db/migration/         # Flyway SQL Server migrations (V1 to V9)
    └── test/
        ├── java/.../domain/          # Pure unit tests for rules and value objects
        ├── java/.../application/     # Pipeline, scope, recovery, and sample file tests
        └── java/.../infrastructure/  # Web/security slice tests + SQL Server Testcontainers (*IT.java)

## Why I built it this way

I wanted to build a backend financial engine that handles the messy parts people usually skip in tutorials: real SQL Server migrations instead of `ddl-auto`, streaming parsers instead of `Files.readAllLines()`, explicit state transitions with lease ownership so two workers can't corrupt a batch, and integration tests against an actual database engine rather than H2.

| Engineering concern | How it's solved here |
| --- | --- |
| Domain isolation | Pure Java domain layer (`domain/`) with zero Spring or JPA dependencies |
| Memory safety on large files | Streaming CSV/JSON readers + chunked persistence |
| Schema evolution | 9 versioned Flyway scripts (`V1`–`V9`) verified by `MigrationIT` |
| Timezone & monetary precision | Explicit `Money` value object and UTC/offset handling verified by `TimeZoneIT` |
| Concurrency & crash recovery | Database lease ownership + `BatchRecoveryScheduler` verified by `ConcurrencyAndRecoveryIT` |
| Verification | 244 automated tests (209 unit/web + 35 Testcontainers SQL Server integration tests) |

Further architectural notes are in `docs/ARCHITECTURE.md` and `docs/VERIFICATION.md`.