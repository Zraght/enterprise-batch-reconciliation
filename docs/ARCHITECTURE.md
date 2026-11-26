🌐 [Versión en español](ARCHITECTURE.esp.md)

# Architecture & Design Decisions (v1.1.0)

## 1. Hexagonal Structure

The codebase follows a clean Ports & Adapters layout so business rules remain completely decoupled from Spring, HTTP, and SQL Server.

                       ┌──────────────────────────── infrastructure ─────────────────────────────┐
HTTP ──► web (REST) ────┤                                                                          │
  (API-key filter)      │   ┌──────────── application ────────────┐                               │
@Scheduled ─────────────┤   │  port.in  (use cases)                │                               │
recovery                │   │  service  (orchestration)            │──► port.out ◄── batch          │
                        │   │                                      │                 (CSV/JSON      │
                        │   │   ┌──────────── domain ──────────┐   │                  readers,       │
                        │   │   │ model · rule (Strategy) · ex.│   │                  file storage, │
                        │   │   └──────────────────────────────┘   │                  async dispatch)│
                        │   └──────────────────────────────────────┘                               │
                        │                                                                          │
                        │   persistence (JPA + JDBC) ─────────────────────────────────► SQL Server │
                        │   config (wiring, properties)                                             │
                        └──────────────────────────────────────────────────────────────────────────┘

### Layer Responsibilities

| Layer | Responsibilities | Dependencies |
|---|---|---|
| `domain` | Value objects (`Money`, `ReferenceCode`, `SourceName`, `SettlementWindow`), ledger/settlement models, reconciliation and audit strategies, and domain exceptions. | Pure JDK 21 only |
| `application` | Inbound use-case interfaces (`port.in`), outbound ports (`port.out`), and pipeline orchestration services. | `domain`, SLF4J |
| `infrastructure` | REST controllers, DTOs, RFC-7807 error handling, API-key filter, JPA entities/repositories, JDBC bulk adapters (`SqlStatements`), streaming file readers, and Spring wiring. | `application`, `domain`, Spring Boot |

Neither `domain` nor `application` contains Spring annotations—everything is wired explicitly in `infrastructure.config.ApplicationConfig`.

JPA entities live strictly in `infrastructure.persistence.entity` and are never leaked into the core domain.

## 2. Batch Processing Pipeline

upload ─► store file (SHA-256 streamed) ─► register batch PENDING ─► 202 Accepted
                                                  │
                                                  │ dispatch (async)
                                                  ▼
tryClaim ─► purge leftovers ─► INGESTION ─► RECONCILIATION ─► AUDIT ─► COMPLETED
(atomic)    (fenced, replay    (0–39%)      (40–79%)           (80–99%)   (100%)
             safe)

                      Any unhandled exception
                                  │
                                  ▼
                     FAILED (reason stored, claims released)

**Ingestion (0–39%):** Streams the uploaded file in bounded chunks (keeping memory flat regardless of file size), validates each record, classifies repeated references, persists rows atomically alongside batch counters, and tracks the earliest/latest settlement timestamps.

**Reconciliation (40–79%):** Pairs external settlements with in-scope ledger entries and classifies discrepancies (Section 3).

**Audit (80–99%):** Evaluates compliance and anomaly rules across the movements that participated in the batch (Section 5).

## 3. Reconciliation Scope & Cross-Batch Rules

A real-world reconciliation engine cannot blindly flag every unsettled ledger transaction as missing—transactions booked minutes before a cutoff naturally settle in the next day's file.

The scope rules below prevent false positives while keeping overdue items visible.

### Core Definitions

| Term | Definition |
|---|---|
| Booking time | `internal_ledger.tx_timestamp`: when the internal core recorded the transaction. |
| Settlement period [start, end] | The window covered by a batch: either declared by the caller (`periodStart` + `periodEnd`) or inferred from the earliest and latest valid timestamps in the file. Stored on the batch with its `periodSource`. |
| Settlement lag | Expected gateway settlement delay after booking (`reconciliation.matching.settlement-lag`, default `P1D` for T+1; `PT0S` for same-day). |
| Due cutoff | `periodEnd - settlementLag`. Persisted per batch so historical results remain explainable even if configuration changes later. |
| In-scope ledger entry | A transaction with the same source, status `POSTED`, and not yet consumed by any completed batch. |

### Matching Outcomes

| Scenario | Engine Behavior |
|---|---|
| Settlement and in-scope ledger entry share a reference | Paired regardless of timestamp differences (settlements can arrive early or late): marked `MATCHED` if amount and currency align, otherwise `AMOUNT_MISMATCH` (`CURRENCY_MISMATCH` carries a null numeric difference). The ledger entry is consumed by the batch. |
| Settlement has no in-scope ledger match | Marked `MISSING_INTERNAL`. When known, detail explains why: `LEDGER_NOT_POSTED` (exists in `PENDING` or `REVERSED` state) or `ALREADY_RECONCILED` (settled in an earlier batch—i.e., a gateway reported the same transaction twice across files). |
| In-scope ledger entry missing from file, booked on or before `dueCutoff` | Marked `MISSING_EXTERNAL` (overdue). Left unconsumed so a future batch can still settle it. |
| In-scope ledger entry missing from file, booked after `dueCutoff` | Classified as deferred (still within normal settlement lag): no discrepancy created, left unconsumed, and counted under `deferredLedgerEntries`. |
| Ledger entry is `PENDING` or `REVERSED` | Excluded from scope and never flagged as `MISSING_EXTERNAL`. |

### Cross-Batch Resolution & Replay Safety

- **Late settlements:** When a subsequent file settles an entry previously flagged `MISSING_EXTERNAL`, the earlier discrepancy record is marked resolved (`resolved_by_batch_id`, `resolved_at`). Historical batch summaries are never mutated; instead, `resolvedByLaterBatches` and `openDiscrepancies` reflect the updated state.
- **Late bookings:** If the internal core posts a transaction after a completed batch already flagged its settlement as `MISSING_INTERNAL`, the next batch for that source pairs them automatically (recorded with `LATE_BOOKING` in detail), consumes the ledger entry, and closes the earlier discrepancy.
- **Persistent visibility:** Unsettled overdue entries continue to surface in subsequent batches of that source until resolved, ensuring open operational items are never silently dropped.
- **Safe retries & failure rollback:** Purging a retried batch reopens any prior discrepancies it had resolved, and a failed batch releases all ledger claims and resolutions so it never blocks subsequent batches.

### Handling Repeated References

| Location | Rule | Rationale |
|---|---|---|
| Internal Ledger | At most one `POSTED` entry per `(source, reference)` via a filtered unique index. `PENDING` and `REVERSED` rows may share the reference. | A reversal legitimately shares the reference of the original transaction; restricting uniqueness to `POSTED` avoids false collisions while guaranteeing unambiguous matching. |
| Settlement File | The first occurrence is reconciled; subsequent occurrences in the same file are rejected as `DUPLICATE_REFERENCE_IDENTICAL` (exact replay) or `DUPLICATE_REFERENCE_CONFLICT` (conflicting amount, currency, or timestamp). | Silently picking one of two conflicting lines in the same file would mask data-quality errors. |

## 4. Monetary Precision & Timezone Guarantees

**Exact arithmetic:** All monetary values use `BigDecimal` in Java and `DECIMAL(19,4)` in SQL Server. Floating-point types (`float`/`double`) are never used for money (enforced by `MigrationIT`).

**Strict scale validation:** `Money` enforces `amount > 0`, up to 15 integer digits, and at most 4 decimal places—excess decimals are rejected rather than rounded. `HALF_EVEN` rounding is applied strictly to derived statistics (such as `reconciliationRatePercent`).

Range validation runs before scale operations to prevent memory exhaustion on malformed scientific notation (e.g., `1E+999999999`).

**Discrepancy formula:** `discrepancyAmount = settlementAmount - ledgerAmount` (treating a missing side as 0, or null on currency mismatch). Totals are aggregated independently per currency.

**Timezone consistency:** Domain timestamps use `Instant`; files require explicit ISO-8601 offsets (`Z` or `±HH:MM`); SQL Server stores UTC `DATETIME2(3)` values truncated to milliseconds.

At startup, `ReconciliationApplication` pins the JVM default timezone to UTC (and omits `hibernate.jdbc.time_zone=UTC`) so JPA entities and raw JDBC statements never drift by the host OS offset (verified by `TimeZoneIT`).

Local time is only used inside `OperatingHoursPolicy` via an explicit `ZoneId`.

## 5. Historical Audit Engine

After matching, `AuditEngine` evaluates configurable Strategy rules (`TemporalDuplicateRule`, `CorporateThresholdRule`, `UnusualTransactionTimeRule`) over movements involved in the batch:

- **Cross-batch & cross-source lookback:** Movements are grouped by account (up to 500 accounts per lookup). The engine queries `POSTED` ledger entries within the configured lookback window (default 30s) across all batches, sources, and reconciliation states. Only the later transaction in a duplicate pair is flagged—catching duplicates even when split across two batches or two different payment gateways.
- **Lifetime alert deduplication:** Storage enforces one alert per `(ledger_tx_id, rule_code)` via unique constraint `UX_aa_ledger_rule`, ensuring a movement re-evaluated during a later cross-batch resolution never fires duplicate alerts.
- **Scope boundary:** Deferred movements are audited once they participate in a batch (matched, mismatched, or reported overdue).

## 6. Concurrency, Fencing & Data Integrity

| Concurrency / Integrity Scenario | Mechanism |
|---|---|
| Two workers claim the same batch | Single atomic `UPDATE ... SET status='PROCESSING', attempt_no=attempt_no+1 ... WHERE status='PENDING' OUTPUT ...` guarantees only one worker wins the lease. |
| Concurrent batches for the same source | Filtered unique index `UX_rb_single_processing_per_source` prevents two batches of the same source from racing over the same ledger rows; the second batch stays `PENDING` for the scheduler. |
| Stale worker wakes up after lease expiration | **Fencing token (`attempt_no`):** Every chunk transaction opens with `UPDATE ... WHERE status='PROCESSING' AND attempt_no=:mine`, acquiring an exclusive lock on the batch row and refreshing its heartbeat. Zero affected rows triggers `BatchOwnershipLostException` and rolls back immediately. |
| Fenced purge on retry | Each purge step runs in a short transaction that first locks the batch row and validates `attempt_no`, then deletes at most 2,000 rows. A stale worker fails before executing any `DELETE`, and the 2,000-row cap prevents SQL Server lock escalation to a full-table lock. |
| Ledger un-marking on failure | Runs in bounded transactions locked under `status='FAILED' AND attempt_no=:mine`, so a concurrent retry immediately halts stale cleanup. |
| Double consumption of a ledger entry | Compare-and-set `UPDATE internal_ledger SET reconciled_batch_id=? WHERE tx_id=? AND reconciled_batch_id IS NULL`; 0 rows aborts and rolls back the chunk. |
| Duplicate concurrent file uploads | Unique constraint on `(source_name, file_sha256)`; the losing thread catches the constraint violation and returns the existing batch idempotently. |

**Isolation & Connection Pooling:** Runs under `READ COMMITTED`, relying on atomic conditional updates and unique indexes rather than heavy locking.

Because the audit phase streams a cursor while querying history and inserting alerts on secondary connections, each active batch uses up to 2 connections (pool size `>= 2 * max-concurrent-batches + 4`).

## 7. Crash Recovery

- Each chunk updates a database-clock heartbeat on the batch row.
- `BatchRecoveryScheduler` runs on application startup and every 30s thereafter, releasing `PROCESSING` batches whose heartbeat exceeds `stale-after` and re-dispatching `PENDING` batches.
- When a recovered batch is claimed by a new worker, it purges any partial derived data from the interrupted attempt and replays cleanly from the stored file.
- Structural file errors transition the batch to `FAILED` with a sanitized reason code; re-uploading the same file re-queues it safely.

## 8. Security & API Hardening

| Area | Implementation |
|---|---|
| API Key Filter | Shared secret in `X-API-Key` (`API_KEY`), deny-by-default across all routes except `/actuator/health`, Swagger UI, and `/v3/api-docs`. Uses SHA-256 constant-time comparison against the container-normalized request path (blocking path-traversal tricks like `/swagger-ui/../api/...`). |
| Fail-Safe Production Startup | Under `prod`, `api-key-required=true` causes startup to abort immediately if `API_KEY` is missing or shorter than 16 characters. Blank keys are only permitted in non-production environments with a startup warning. |
| Explicit Profiles | No default active profile. `SeedController` is only registered when `dev` or `test` is explicitly activated (`SPRING_PROFILES_ACTIVE=dev`). `prod` disables Swagger/OpenAPI by default and hides health details. |
| Input & Path Hardening | `sourceName` is restricted to safe identifier characters; uploaded filenames are stripped to their final path segment, stripped of control characters, capped at 255 chars, and stored under server-generated UUID names. Unknown JSON properties are rejected. |
| Sanitized Error Responses | Errors conform to RFC 7807 (`application/problem+json`) with stable application error codes. Stack traces, SQL text, hostnames, and local filesystem paths are never exposed in API responses or database failure reasons. |
| Security Headers | Responses under `/api/**` include `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, and `Cache-Control: no-store`. |

**Scope note:** The API-key filter is designed for internal service-to-service traffic or deployments behind an API gateway. For multi-user RBAC, token rotation, or caller audit trails, place an OAuth2/OIDC gateway in front of the service.

## 9. Technology Choices

**Plain Services over Spring Batch:** Spring Batch introduces its own `BATCH_*` metadata tables and restart semantics that conflict with per-source fencing and cross-batch ledger resolution. Custom chunked services keep the schema clean (V1–V9) and allow fast in-memory unit testing without a Spring context.

**Bounded Virtual Threads:** `AsyncBatchDispatcher` uses Java 21 virtual threads capped by a `Semaphore` (`max-concurrent-batches`) on a private executor (avoiding overriding Spring Boot's default `applicationTaskExecutor`). Setting `virtual-threads=false` falls back to a classic fixed thread pool.

**JPA + JDBC Hybrid Persistence:** Spring Data JPA handles batch lifecycle entities and dynamic paginated discrepancy queries, while `JdbcTemplate` (`SqlStatements`) handles high-throughput bulk inserts (bypassing Hibernate's `IDENTITY` batching limitation on SQL Server) and atomic state transitions.

Schema DDL is owned exclusively by Flyway (`ddl-auto=none`).

## 10. Known Operational Limits

- **Per-batch memory footprint:** Holds one processing chunk plus an in-memory set of seen reference codes for duplicate detection (~100 MB per million records in a single file).
- **Per-account audit grouping:** Loads one account's batch movements in memory during audit evaluation; a single account generating millions of transactions within a single batch is outside the target workload shape.
- **Local file storage:** Uploaded files are persisted on the node handling the HTTP request; multi-node horizontal deployments should implement a shared storage adapter for `FileStoragePort` (e.g., S3 or Azure Blob Storage).
- **Single settlement lag per deployment:** `settlement-lag` is configured globally per deployment rather than overridden per payment gateway.