🌐 [English version](VERIFICATION.md)

# Guía de Verificación y Pruebas (v1.1.0)

Esta guía detalla cómo ejecutar las suites de pruebas automatizadas, levantar el servicio localmente contra SQL Server 2022 y validar el comportamiento de extremo a extremo usando los archivos de muestra incluidos.

## 0. Prerrequisitos

- **JDK 21** (Eclipse Temurin o equivalente)
- **Maven 3.9+**
- **Docker** (Docker Desktop / Docker Engine con WSL2 o socket Linux, necesario para las pruebas de integración con Testcontainers y SQL Server local)

## 1. Pruebas Unitarias y Web (`mvn clean test`)

Ejecuta todas las pruebas unitarias y de capa web Spring MVC mediante Maven Surefire sin necesidad de Docker.

mvn clean test

- **Resultado esperado:** `BUILD SUCCESS` con **209 pruebas ejecutadas, 0 fallos, 0 errores**.
- **Cobertura:** Reglas de dominio y objetos de valor (`MoneyTest`, `ReconciliationRulesTest`, `AuditRulesTest`, `SettlementWindowTest`), orquestación del flujo (`BatchPipelineTest`, `ReconciliationScopeTest`, `RecoveryAndFailureTest`, `SampleFilesTest`, `SyntheticDataPipelineTest`), lectores en streaming (`FileReadersTest`), despacho asíncrono (`AsyncBatchDispatcherTest`) y controladores REST/seguridad (`BatchControllerTest`, `ApiKeyFilterTest`, `SeedControllerDisabledInProdTest`).

## 2. Pruebas de Integración contra SQL Server Real (`mvn verify`)

Ejecuta el ciclo completo incluyendo las pruebas de integración Failsafe (`*IT.java`). Testcontainers (`1.21.4`) descarga y levanta automáticamente un contenedor real `mcr.microsoft.com/mssql/server:2022-latest`, aplica las 9 migraciones de Flyway y valida el comportamiento real en base de datos.

mvn verify

- **Resultado esperado:** `BUILD SUCCESS` con **35 pruebas de integración ejecutadas, 0 fallos, 0 errores, 0 omitidas**:
  - `MigrationIT` — Verifica las migraciones Flyway `V1`–`V9`, columnas monetarias `DECIMAL(19,4)`, índices únicos filtrados y restricciones `CHECK`.
  - `ReconciliationFlowIT` — Prueba de punta a punta de ingesta, cruce, persistencia de discrepancias y deduplicación de alertas de auditoría.
  - `ScopeAndCrossBatchIT` — Valida períodos declarados/inferidos, cortes de exigibilidad (`dueCutoff`), asientos diferidos, liquidaciones tardías y registros contables tardíos (*late bookings*).
  - `ConcurrencyAndRecoveryIT` — Verifica la toma atómica de lotes, unicidad de lote activo por fuente, *fencing* por `attempt_no`, purga en bloques acotados y recuperación ante caídas.
  - `ApiKeySecurityIT` — Comprueba la validación en tiempo constante de `X-API-Key` y el bloqueo de arranque en perfil `prod`.
  - `TimeZoneIT` — Confirma que las marcas de tiempo UTC viajan de forma idéntica entre JPA y JDBC incluso cuando el servidor anfitrión tiene otra zona horaria (ej. `America/Lima`).

> **Nota:** Si Docker no está encendido, Testcontainers omite las pruebas `*IT.java` en lugar de marcarlas como fallidas. Asegúrate siempre de ver `Skipped: 0` al validar cambios en base de datos.

## 3. Arranque Local y Validación de Seguridad

### Levantar el servicio en modo `dev`

cp .env.example .env
docker compose up -d

# Cargar variables de entorno (Linux/macOS/Git Bash)
set -a
source .env
set +a

SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run

*(En PowerShell de Windows, define las variables de `.env` en tu sesión o en la configuración de ejecución de tu IDE antes de lanzar `mvn spring-boot:run`.)*

- **Logs esperados al arrancar:** `Successfully applied 9 migrations` (Flyway) seguido de `Started ReconciliationApplication`.
- **Health check:** `curl http://localhost:8080/actuator/health` devuelve `{"status":"UP"}`.
- **Swagger UI:** Disponible en `http://localhost:8080/swagger-ui.html`.

### Validaciones de seguridad en producción

- Iniciar con `SPRING_PROFILES_ACTIVE=prod` y una `API_KEY` vacía o menor a 16 caracteres **detiene el arranque de inmediato** con un error explícito de configuración.
- Iniciar sin perfil activo (`SPRING_PROFILES_ACTIVE` sin definir) mantiene `SeedController` deshabilitado (`POST /api/v1/ledger/seed` responde `404 Not Found`).

## 4. Prueba Rápida Automatizada de Endpoints (`scripts/smoke-test.sh`)

Valida los 5 endpoints de la API contra el servidor en ejecución (requiere `curl` y `jq`):

API_URL=http://localhost:8080 API_KEY=<tu-api-key> ./scripts/smoke-test.sh

El script genera 400 asientos sintéticos, espera a que el lote pase a `COMPLETED`, compara cada métrica del resumen contra los valores esperados, prueba paginación/filtros de discrepancias y comprueba la idempotencia al subir un CSV (`202` seguido de `200`).

- **Salida esperada:** Finaliza con `ALL CHECKS PASSED` (código de salida `0`).

## 5. Verificación Funcional Manual con Archivos de Muestra

Para comprobar manualmente la conciliación entre lotes (*cross-batch*) contra tu instancia local de SQL Server:

### 5.1. Cargar el libro mayor del Día 1 y subir la muestra de Niubiz

sqlcmd -S localhost,1433 -U reconciliation_app -P "$DB_PASSWORD" -C -d ReconciliationDB -i samples/sample_ledger.sql

curl -H "X-API-Key: $API_KEY" \
  -F "file=@samples/settlements_niubiz_sample.csv" \
  -F "sourceName=NIUBIZ" \
  http://localhost:8080/api/v1/batches/upload

curl -H "X-API-Key: $API_KEY" \
  http://localhost:8080/api/v1/batches/1/summary

**Resumen esperado Día 1:**

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

### 5.2. Cargar el asiento tardío (*late booking*) y subir el archivo del Día 2

sqlcmd -S localhost,1433 -U reconciliation_app -P "$DB_PASSWORD" -C -d ReconciliationDB -i samples/sample_ledger_late_booking.sql

curl -H "X-API-Key: $API_KEY" \
  -F "file=@samples/settlements_niubiz_sample_day2.csv" \
  -F "sourceName=NIUBIZ" \
  http://localhost:8080/api/v1/batches/upload

**Resultado esperado Día 2:** El lote 2 reporta `matchedTransactions: 4, discrepancyCount: 0`, y al volver a consultar el resumen del lote 1 ahora refleja `resolvedByLaterBatches: 2, openDiscrepancies: 4`.

### 5.3. Subir la muestra JSON de Izipay (`sourceName=IZIPAY`)

- Reporta `evaluatedReferences: 4`, `matchedTransactions: 2` y `totalDiscrepancyAmount.PEN: 65.5000`.

*(También puedes ejecutar `SAMPLES=1 ./scripts/smoke-test.sh` para automatizar las verificaciones del Día 1; estas mismas aserciones se validan continuamente en `SampleFilesTest`.)*

## 6. Línea Base Verificada (v1.1.0)

| Capa de Verificación | Comando / Herramienta | Estado |
|---|---|---|
| Compilación (`main` + `test`) | `mvn test-compile` (JDK 21) | **Aprobado (0 errores)** |
| Suite Unitaria y Web | `mvn test` (Maven Surefire) | **Aprobado (209 / 209)** |
| Suite de Integración SQL Server 2022 | `mvn verify` (Maven Failsafe + Testcontainers 1.21.4) | **Aprobado (35 / 35)** |
| Migraciones de Esquema (`V1`–`V9`) | Flyway en SQL Server 2022 (`MigrationIT`) | **Aprobado** |
| Consistencia de Zona Horaria (`UTC` y `America/Lima`) | `TimeZoneIT` | **Aprobado** |