# docs/API.md (English)

🌐 [Versión en español](API.esp.md)

# REST API Reference (v1.1.0)

All endpoints live under the `/api/v1` base path.

When running locally in `dev` or `test` mode, interactive Swagger documentation is available at `http://localhost:8080/swagger-ui.html` (and the raw OpenAPI spec at `/v3/api-docs`). Both are disabled by default in `prod` unless `SWAGGER_UI_ENABLED=true` is explicitly set.

## Authentication

When `API_KEY` is configured (which is mandatory in `prod`), every request must include it in the `X-API-Key` header:

X-API-Key: your-secret-api-key

The only public exceptions are `/actuator/health`, `/swagger-ui.html`, and `/v3/api-docs`. If the header is missing or invalid, the API responds with `401 Unauthorized` (`code: UNAUTHORIZED`) without revealing whether the key was missing or incorrect. In local development with no `API_KEY` configured, authentication is bypassed.

## Settlement File Formats

Both `.csv` and `.json` files must provide four required fields (column/property names are case-insensitive):

| Field | Validation Rules |
| --- | --- |
| `external_ref` | 1–64 characters (`A-Z`, `a-z`, `0-9`, `.`, `_`, `-`, `/`). Automatically normalized to uppercase. |
| `amount` | Positive decimal (`> 0`), up to 15 integer digits and **at most 4 decimal places** (records with >4 decimals are rejected, never rounded). No thousands separators. |
| `currency` | 3-letter ISO-4217 currency code (e.g., `PEN`, `USD`, `EUR`). |
| `settlement_timestamp` | ISO-8601 timestamp **with explicit offset or `Z`** (e.g., `2026-03-10T15:00:00Z` or `2026-03-10T10:00:00-05:00`). |

- **CSV files:** Must be UTF-8 encoded (BOM is tolerated), RFC-4180 compliant, and include a header row.
- **JSON files:** Must contain a root array of flat JSON objects (`amount` may be passed as a number or string).

### Row-level error handling

Invalid rows never abort the entire batch. Instead, they are quarantined in `batch_invalid_records` with a specific reason code and tallied in the batch summary:

- **Reference errors:** `MISSING_REFERENCE`, `INVALID_REFERENCE`, `DUPLICATE_REFERENCE_IDENTICAL`, `DUPLICATE_REFERENCE_CONFLICT`
- **Amount & currency errors:** `MISSING_AMOUNT`, `INVALID_AMOUNT`, `AMOUNT_SCALE_EXCEEDED`, `AMOUNT_OUT_OF_RANGE`, `MISSING_CURRENCY`, `INVALID_CURRENCY`
- **Timestamp & structure errors:** `MISSING_TIMESTAMP`, `INVALID_TIMESTAMP`, `MALFORMED_ROW`

## Endpoints

### 1. Upload & Register a Settlement Batch

`POST /api/v1/batches/upload`

Accepts `multipart/form-data` with the following fields:

- `file` *(required)*: The `.csv` or `.json` settlement file.
- `sourceName` *(required)*: Payment processor or source identifier (up to 100 characters: letters, digits, spaces, `.`, `_`, `-`).
- `periodStart` & `periodEnd` *(optional, must be provided together)*: ISO-8601 instants defining the settlement window covered by the file. If omitted, the engine infers the period from the timestamps inside the file. This window determines which internal ledger entries are considered *due* when checking for `MISSING_EXTERNAL` discrepancies (see [ARCHITECTURE.md](ARCHITECTURE.md) §3).

**Example Request**

curl -i -X POST http://localhost:8080/api/v1/batches/upload \
  -H "X-API-Key: $API_KEY" \
  -F "file=@samples/settlements_niubiz_sample.csv" \
  -F "sourceName=NIUBIZ"

**Response (`202 Accepted`)**

HTTP/1.1 202 Accepted
Location: /api/v1/batches/1/status

{
  "batchId": 1,
  "status": "PENDING",
  "outcome": "CREATED",
  "statusUrl": "/api/v1/batches/1/status"
}

| HTTP Status | Outcome / Meaning |
| --- | --- |
| `202 Accepted` | New batch queued (`CREATED`), or a previously failed batch for the exact same file re-queued (`RETRIED`, adopting the new period if provided). |
| `200 OK` | **Idempotent replay (`EXISTING`):** The exact same `sourceName` and file content hash was already registered. |
| `400 Bad Request` | Blank/invalid `sourceName`, empty or missing file, unsupported file extension, or invalid date range (`INVALID_PERIOD`). |
| `401 Unauthorized` | Missing or invalid `X-API-Key`. |
| `409 Conflict` | Same file was already registered under a **different** declared period (`BATCH_PERIOD_CONFLICT`). |
| `413 Payload Too Large` | File exceeds `UPLOAD_MAX_FILE_SIZE`. |

### 2. Poll Batch Processing Status

`GET /api/v1/batches/{batchId}/status`

Tracks asynchronous execution across lifecycle statuses (`PENDING` → `PROCESSING` → `COMPLETED` or `FAILED`) and processing phases:

- `QUEUED` (0%)
- `INGESTION` (0–39%)
- `RECONCILIATION` (40–79%)
- `AUDIT` (80–99%)
- `DONE` (100%)

Once ingestion finishes, the response also includes `periodStart`, `periodEnd`, `periodSource` (`DECLARED` or `INFERRED`), and `dueCutoff`. Upon completion, `deferredLedgerEntries` is populated (or `failureReason` if the batch ended in `FAILED`).

- **Errors:** `400 Bad Request` if `batchId` is not a positive integer; `404 Not Found` if the batch does not exist.

### 3. Get Batch Reconciliation Summary

`GET /api/v1/batches/{batchId}/summary`

Returns full metrics for a `COMPLETED` batch. Calling this endpoint while a batch is still running or failed returns `409 Conflict` (`BATCH_NOT_COMPLETED`).

**Example Response (`200 OK`)**

*(Actual output produced by `samples/settlements_niubiz_sample.csv`)*

{
  "batchId": 1,
  "status": "COMPLETED",
  "sourceName": "NIUBIZ",
  "totalRecords": 14,
  "validRecords": 11,
  "invalidRecords": 3,
  "invalidRecordsByReason": {
    "DUPLICATE_REFERENCE_CONFLICT": 1,
    "DUPLICATE_REFERENCE_IDENTICAL": 1,
    "INVALID_AMOUNT": 1
  },
  "periodStart": "2026-03-10T06:31:00Z",
  "periodEnd": "2026-03-10T16:01:00Z",
  "periodSource": "INFERRED",
  "dueCutoff": "2026-03-09T16:01:00Z",
  "evaluatedReferences": 12,
  "matchedTransactions": 6,
  "discrepancyCount": 6,
  "resolvedByLaterBatches": 0,
  "openDiscrepancies": 6,
  "discrepanciesByStatus": {
    "MISSING_INTERNAL": 3,
    "MISSING_EXTERNAL": 1,
    "AMOUNT_MISMATCH": 2
  },
  "totalDiscrepancyAmount": {
    "PEN": 865.0000
  },
  "reconciliationRatePercent": 50.00,
  "deferredLedgerEntries": 2,
  "alertsGenerated": 3,
  "alertsBySeverity": {
    "LOW": 0,
    "MEDIUM": 1,
    "HIGH": 2,
    "CRITICAL": 0
  },
  "startedAt": "2026-03-10T16:05:00Z",
  "completedAt": "2026-03-10T16:05:02Z"
}

**How key metrics are calculated:**

- `reconciliationRatePercent`: `(matchedTransactions / evaluatedReferences) * 100`, rounded to 2 decimal places using half-even (banker's) rounding.
- `totalDiscrepancyAmount`: Sum of absolute discrepancy amounts **grouped by currency**. Currency mismatches have no numeric difference and are excluded from this sum.
- `discrepancyCount` vs. `openDiscrepancies`: `discrepancyCount` is the total discrepancies originally found in this batch; `resolvedByLaterBatches` and `openDiscrepancies` update dynamically if a subsequent batch settles a previously missing transaction.
- `deferredLedgerEntries`: Ledger entries still within the configured settlement lag window—counted as pending rather than discrepancies.

### 4. Query & Filter Discrepancies

`GET /api/v1/batches/{batchId}/discrepancies`

Returns a paginated list of discrepancies for a completed batch, ordered by `resultId`.

| Query Parameter | Default | Description |
| --- | --- | --- |
| `matchStatus` | *(all)* | Filter by `MISSING_INTERNAL`, `MISSING_EXTERNAL`, or `AMOUNT_MISMATCH` (passing `MATCHED` returns `400`). |
| `reference` | *(none)* | Case-insensitive substring match against `referenceCode`. |
| `currency` | *(none)* | Filter by ISO-4217 currency code. |
| `resolved` | *(both)* | `true` = closed by a later batch; `false` = still open. |
| `page` | `0` | Zero-based page index. |
| `size` | `50` | Page size (`1` to `500`). |

Each item in the page includes `resultId`, `referenceCode`, `matchStatus`, `discrepancyAmount`, `currency`, `ledgerTxId`, `settlementId`, `detail`, and `resolvedByBatchId`.

- **Calculation:** `discrepancyAmount = settlementAmount - ledgerAmount` (using `0` when one side is missing, or `null` when currencies differ).
- **Context details:** The `detail` field explains the specific finding (e.g., `CURRENCY_MISMATCH`, `AMOUNT_DIFFERENCE`, `LEDGER_NOT_POSTED: status REVERSED`, `ALREADY_RECONCILED: batch 3`, or `LATE_BOOKING`).

### 5. Seed Synthetic Data (Dev & Test Only)

`POST /api/v1/ledger/seed`

Generates realistic internal ledger entries and matching/mismatched settlement files for local testing. This endpoint is strictly disabled in `prod` (returns `404 Not Found`).

**Example Request**

curl -X POST http://localhost:8080/api/v1/ledger/seed \
  -H "X-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "sourceName": "NIUBIZ",
    "count": 1000,
    "randomSeed": 42,
    "submit": true
  }'

- Accepts `count` up to `200,000` and configurable anomaly/discrepancy rates (`0.0` to `0.5`).
- Returns `201 Created` with an `expected` summary object (`matched`, `amountMismatch`, `missingExternal`, `missingInternal`, `invalidRecords`, `deferredLedgerEntries`, `minTemporalDuplicateAlerts`) so you can verify engine accuracy deterministically.
- When `submit: false`, it generates the settlement file in storage and returns `settlementFileName` without exposing server filesystem paths.
- Returns `409 Conflict` if the exact same seed configuration was already applied to that `sourceName`.

## Error Responses (RFC 7807)

All error responses follow the standard `application/problem+json` format. Stack traces, SQL statements, hostnames, and internal file paths are never exposed.

{
  "type": "about:blank",
  "title": "Invalid request",
  "status": 400,
  "detail": "File extension '.txt' is not supported; expected .csv or .json",
  "code": "UNSUPPORTED_FILE_FORMAT"
}

Field validation errors include an additional `errors` map (`field -> message`).

| HTTP Status | Application Error Codes |
| --- | --- |
| `400 Bad Request` | `VALIDATION_FAILED`, `MALFORMED_BODY`, `INVALID_PARAMETER`, `UNSUPPORTED_FILE_FORMAT`, `EMPTY_FILE`, `INVALID_FILE_NAME`, `INVALID_SOURCE`, `INVALID_PERIOD`, `INVALID_FILTER`, `INVALID_PAGE_SIZE`, `INVALID_COUNT`, `INVALID_RATE` |
| `401 Unauthorized` | `UNAUTHORIZED` |
| `404 Not Found` | `BATCH_NOT_FOUND` |
| `409 Conflict` | `BATCH_NOT_COMPLETED`, `BATCH_PERIOD_CONFLICT`, `DATA_CONFLICT` |
| `413 Payload Too Large` | `FILE_TOO_LARGE` |
| `500 Internal Server Error` | `INTERNAL_ERROR` |