🌐 [English version](API.md)

# Referencia de la API REST (v1.1.0)

Todos los endpoints están bajo la ruta base `/api/v1`.

Al ejecutar el servicio localmente en modo `dev` o `test`, la documentación interactiva de Swagger está disponible en `http://localhost:8080/swagger-ui.html` (y la especificación OpenAPI en `/v3/api-docs`). Ambos están deshabilitados por defecto en `prod`, a menos que se configure explícitamente `SWAGGER_UI_ENABLED=true`.

## Autenticación

Cuando `API_KEY` está configurada (obligatorio en `prod`), todas las peticiones deben incluirla en el encabezado `X-API-Key`:

X-API-Key: tu-clave-secreta-api

Las únicas excepciones públicas son `/actuator/health`, `/swagger-ui.html` y `/v3/api-docs`.

Si el encabezado falta o es incorrecto, la API responde con `401 Unauthorized` (`code: UNAUTHORIZED`), sin revelar si la clave está ausente o es incorrecta.

En desarrollo local, si no se configura `API_KEY`, la autenticación se omite.

## Formatos de archivos de liquidación

Tanto los archivos `.csv` como `.json` deben incluir cuatro campos obligatorios. Los nombres de las columnas o propiedades no distinguen entre mayúsculas y minúsculas.

| Campo | Reglas de validación |
|---|---|
| `external_ref` | Entre 1 y 64 caracteres (`A-Z`, `a-z`, `0-9`, `.`, `_`, `-`, `/`). Se normaliza automáticamente a mayúsculas. |
| `amount` | Número decimal positivo (`> 0`), con hasta 15 dígitos enteros y **un máximo de 4 decimales**. Los registros con más de 4 decimales se rechazan; nunca se redondean. No se permiten separadores de miles. |
| `currency` | Código de moneda ISO-4217 de 3 letras (por ejemplo, `PEN`, `USD`, `EUR`). |
| `settlement_timestamp` | Marca de tiempo ISO-8601 **con un desplazamiento horario explícito o `Z`** (por ejemplo, `2026-03-10T15:00:00Z` o `2026-03-10T10:00:00-05:00`). |

- **Archivos CSV:** Deben utilizar codificación UTF-8 (se tolera BOM), cumplir con el formato RFC-4180 e incluir una fila de encabezados.
- **Archivos JSON:** Deben contener un arreglo raíz de objetos JSON planos. El campo `amount` puede enviarse como número o como cadena de texto.

### Manejo de errores por registro

Los registros inválidos no interrumpen el procesamiento del lote completo. En su lugar, se aíslan en `batch_invalid_records`, se les asigna un código de motivo específico y se contabilizan en el resumen del lote.

- **Errores de referencia:** `MISSING_REFERENCE`, `INVALID_REFERENCE`, `DUPLICATE_REFERENCE_IDENTICAL`, `DUPLICATE_REFERENCE_CONFLICT`.
- **Errores de monto y moneda:** `MISSING_AMOUNT`, `INVALID_AMOUNT`, `AMOUNT_SCALE_EXCEEDED`, `AMOUNT_OUT_OF_RANGE`, `MISSING_CURRENCY`, `INVALID_CURRENCY`.
- **Errores de fecha y estructura:** `MISSING_TIMESTAMP`, `INVALID_TIMESTAMP`, `MALFORMED_ROW`.

## Endpoints

### 1. Subir y registrar un lote de liquidación

`POST /api/v1/batches/upload`

Recibe una petición `multipart/form-data` con los siguientes campos:

- `file` *(obligatorio)*: Archivo de liquidación en formato `.csv` o `.json`.
- `sourceName` *(obligatorio)*: Identificador de la pasarela de pago o fuente. Admite hasta 100 caracteres: letras, dígitos, espacios, `.`, `_` y `-`.
- `periodStart` y `periodEnd` *(opcionales, pero deben enviarse juntos)*: Instantes ISO-8601 que definen el período de liquidación cubierto por el archivo.

Si se omiten `periodStart` y `periodEnd`, el motor infiere el período a partir de las marcas de tiempo contenidas en el archivo. Esta ventana determina qué movimientos internos del libro mayor se consideran exigibles (*due*) al evaluar discrepancias `MISSING_EXTERNAL` (consulta [ARCHITECTURE.md](ARCHITECTURE.md), sección 3).

**Ejemplo de petición**

curl -i -X POST http://localhost:8080/api/v1/batches/upload \
  -H "X-API-Key: $API_KEY" \
  -F "file=@samples/settlements_niubiz_sample.csv" \
  -F "sourceName=NIUBIZ"

**Respuesta (`202 Accepted`)**

HTTP/1.1 202 Accepted
Location: /api/v1/batches/1/status

{
  "batchId": 1,
  "status": "PENDING",
  "outcome": "CREATED",
  "statusUrl": "/api/v1/batches/1/status"
}

| Código HTTP | Resultado / Significado |
|---|---|
| `202 Accepted` | Se encola un nuevo lote (`CREATED`) o se vuelve a encolar un lote anterior que falló, siempre que corresponda exactamente al mismo archivo (`RETRIED`). En este último caso, se adopta el nuevo período si se proporciona. |
| `200 OK` | **Repetición idempotente (`EXISTING`):** ya se había registrado el mismo `sourceName` y el mismo hash del contenido del archivo. |
| `400 Bad Request` | `sourceName` vacío o inválido, archivo ausente o vacío, extensión no admitida o rango de fechas inválido (`INVALID_PERIOD`). |
| `401 Unauthorized` | Encabezado `X-API-Key` ausente o inválido. |
| `409 Conflict` | El mismo archivo ya se había registrado con un período declarado **diferente** (`BATCH_PERIOD_CONFLICT`). |
| `413 Payload Too Large` | El archivo supera el límite definido por `UPLOAD_MAX_FILE_SIZE`. |

### 2. Consultar el estado de procesamiento del lote

`GET /api/v1/batches/{batchId}/status`

Permite realizar el seguimiento de la ejecución asíncrona del lote a través de sus estados de ciclo de vida (`PENDING` → `PROCESSING` → `COMPLETED` o `FAILED`) y sus fases de procesamiento:

- `QUEUED` (0 %): El lote está en cola y todavía no ha comenzado el procesamiento.
- `INGESTION` (0–39 %): Se están leyendo y registrando los datos del archivo.
- `RECONCILIATION` (40–79 %): Se están comparando los registros de liquidación con los movimientos del libro mayor.
- `AUDIT` (80–99 %): Se están generando y registrando las alertas de auditoría.
- `DONE` (100 %): El procesamiento ha finalizado.

Una vez completada la ingesta, la respuesta también incluye `periodStart`, `periodEnd`, `periodSource` (`DECLARED` o `INFERRED`) y `dueCutoff`.

Cuando el lote termina correctamente, se informa `deferredLedgerEntries`. Si termina en estado `FAILED`, se proporciona `failureReason`.

- **Errores:**
  - `400 Bad Request`: `batchId` no es un número entero positivo.
  - `404 Not Found`: El lote no existe.

### 3. Obtener el resumen de conciliación del lote

`GET /api/v1/batches/{batchId}/summary`

Devuelve las métricas completas de un lote que se encuentre en estado `COMPLETED`.

Si se consulta mientras el lote todavía está en procesamiento o si terminó en estado `FAILED`, la API responde con `409 Conflict` (`BATCH_NOT_COMPLETED`).

**Ejemplo de respuesta (`200 OK`)**

*(Salida real producida por `samples/settlements_niubiz_sample.csv`.)*

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

**Cómo se calculan las métricas principales**

- `reconciliationRatePercent`: Se calcula dividiendo `matchedTransactions` entre `evaluatedReferences` y multiplicando el resultado por 100. El valor se redondea a 2 decimales utilizando el método *half-even* (redondeo bancario).
- `totalDiscrepancyAmount`: Suma de los valores absolutos de las diferencias, **agrupados por moneda**. Las discrepancias por diferencias de moneda no tienen una diferencia numérica y, por tanto, no se incluyen en esta suma.
- `discrepancyCount` y `openDiscrepancies`: `discrepancyCount` representa el total de discrepancias detectadas originalmente en el lote. En cambio, `resolvedByLaterBatches` y `openDiscrepancies` se actualizan dinámicamente cuando un lote posterior liquida una transacción que anteriormente estaba pendiente.
- `deferredLedgerEntries`: Cantidad de movimientos del libro mayor que todavía se encuentran dentro del período de espera de liquidación (*settlement lag*). Se contabilizan como pendientes, no como discrepancias.

### 4. Consultar y filtrar discrepancias

`GET /api/v1/batches/{batchId}/discrepancies`

Devuelve una lista paginada de las discrepancias de un lote completado, ordenadas por `resultId`.

| Parámetro de consulta | Valor predeterminado | Descripción |
|---|---|---|
| `matchStatus` | Todos | Filtra por `MISSING_INTERNAL`, `MISSING_EXTERNAL` o `AMOUNT_MISMATCH`. Enviar `MATCHED` devuelve `400`. |
| `reference` | Ninguno | Busca coincidencias parciales, sin distinguir entre mayúsculas y minúsculas, en `referenceCode`. |
| `currency` | Ninguno | Filtra por código de moneda ISO-4217. |
| `resolved` | Ambos | `true` = discrepancias cerradas por un lote posterior; `false` = discrepancias que continúan abiertas. |
| `page` | `0` | Índice de página basado en cero. |
| `size` | `50` | Cantidad de elementos por página (entre `1` y `500`). |

Cada elemento de la página incluye los siguientes campos:

`resultId`, `referenceCode`, `matchStatus`, `discrepancyAmount`, `currency`, `ledgerTxId`, `settlementId`, `detail` y `resolvedByBatchId`.

- **Cálculo de la diferencia:** `discrepancyAmount = settlementAmount - ledgerAmount`. Cuando falta uno de los dos registros, el valor ausente se considera `0`. Si las monedas son diferentes, el resultado es `null`.
- **Detalle del resultado:** El campo `detail` explica el motivo específico de la discrepancia. Algunos ejemplos son `CURRENCY_MISMATCH`, `AMOUNT_DIFFERENCE`, `LEDGER_NOT_POSTED: status REVERSED`, `ALREADY_RECONCILED: batch 3` y `LATE_BOOKING`.

### 5. Generar datos sintéticos (solo para `dev` y `test`)

`POST /api/v1/ledger/seed`

Genera movimientos internos del libro mayor y archivos de liquidación con coincidencias y discrepancias para realizar pruebas locales.

Este endpoint está estrictamente deshabilitado en el perfil `prod` y devuelve `404 Not Found`.

**Ejemplo de petición**

curl -X POST http://localhost:8080/api/v1/ledger/seed \
  -H "X-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "sourceName": "NIUBIZ",
    "count": 1000,
    "randomSeed": 42,
    "submit": true
  }'

- Admite un valor de `count` de hasta `200,000` registros y tasas configurables de anomalías y discrepancias entre `0.0` y `0.5`.
- Devuelve `201 Created` junto con un objeto `expected` que contiene el resumen de resultados esperados: `matched`, `amountMismatch`, `missingExternal`, `missingInternal`, `invalidRecords`, `deferredLedgerEntries` y `minTemporalDuplicateAlerts`. Estos valores permiten comprobar de forma determinista la precisión del motor.
- Cuando `submit: false`, genera el archivo de liquidación en el almacenamiento y devuelve `settlementFileName`, sin exponer las rutas del sistema de archivos del servidor.
- Devuelve `409 Conflict` si la misma configuración de semilla ya fue aplicada a ese `sourceName`.

## Respuestas de error (RFC 7807)

Todas las respuestas de error siguen el formato estándar `application/problem+json`.

Por seguridad, nunca se exponen trazas de ejecución (*stack traces*), consultas SQL, nombres de host ni rutas internas del sistema de archivos.

**Ejemplo de respuesta**

{
  "type": "about:blank",
  "title": "Invalid request",
  "status": 400,
  "detail": "File extension '.txt' is not supported; expected .csv or .json",
  "code": "UNSUPPORTED_FILE_FORMAT"
}

Los errores de validación de campos incluyen, además, un mapa `errors` con la relación `campo -> mensaje`.

| Código HTTP | Códigos de error de la aplicación |
|---|---|
| `400 Bad Request` | `VALIDATION_FAILED`, `MALFORMED_BODY`, `INVALID_PARAMETER`, `UNSUPPORTED_FILE_FORMAT`, `EMPTY_FILE`, `INVALID_FILE_NAME`, `INVALID_SOURCE`, `INVALID_PERIOD`, `INVALID_FILTER`, `INVALID_PAGE_SIZE`, `INVALID_COUNT`, `INVALID_RATE` |
| `401 Unauthorized` | `UNAUTHORIZED` |
| `404 Not Found` | `BATCH_NOT_FOUND` |
| `409 Conflict` | `BATCH_NOT_COMPLETED`, `BATCH_PERIOD_CONFLICT`, `DATA_CONFLICT` |
| `413 Payload Too Large` | `FILE_TOO_LARGE` |
| `500 Internal Server Error` | `INTERNAL_ERROR` |