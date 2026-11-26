🌐 [English version](README.md)

# enterprise-batch-reconciliation

Construí este proyecto porque conciliar archivos de pasarelas de pago (Niubiz, Izipay) contra un libro mayor interno usando hojas de cálculo deja de escalar en cuanto sube el volumen, las liquidaciones llegan con un día de retraso o alguien vuelve a subir el mismo archivo por error. Quería un motor backend que tome archivos crudos de liquidación, los procese en streaming sin saturar la memoria, los cruce contra SQL Server y marque discrepancias y anomalías de auditoría automáticamente.

Es un servicio REST en Spring Boot 3.4 / Java 21 diseñado bajo arquitectura hexagonal. Subes un archivo CSV o JSON de liquidaciones, el motor registra el lote con su hash SHA-256 (detectando cargas duplicadas al instante), procesa los registros de forma asíncrona por bloques (chunks) y expone endpoints para seguir el progreso y consultar discrepancias.

## Inicio rápido

1. Levantar SQL Server 2022 e inicializar el esquema: `docker compose up -d`
2. Ejecutar la aplicación: `mvn spring-boot:run`
3. Subir un archivo de liquidación de ejemplo (Niubiz):

curl -X POST http://localhost:8080/api/v1/batches \
  -H "X-API-Key: local-dev-key" \
  -F "file=@samples/settlements_niubiz_sample.csv" \
  -F "source=NIUBIZ" \
  -F "periodStart=2026-03-01" \
  -F "periodEnd=2026-03-01"

## Primeros pasos

Requiere **Java 21**, **Maven 3.9+** y **Docker** (para SQL Server local y las pruebas de integración).

Clonar el repositorio:

git clone https://github.com/YOUR_USERNAME/enterprise-batch-reconciliation.git
cd enterprise-batch-reconciliation

Copiar variables de entorno y levantar SQL Server:

cp .env.example .env
docker compose up -d

Una vez que SQL Server esté activo, crea la base de datos ejecutando `database/01_create_database.sql`. Flyway se encarga de aplicar las 9 migraciones `V1`–`V9` automáticamente al arrancar:

mvn spring-boot:run

La interfaz de Swagger UI queda disponible en `http://localhost:8080/swagger-ui.html`.

### Ejecutar la suite de pruebas

Las pruebas unitarias y web corren con Surefire:

mvn test

Las pruebas de integración (`*IT.java`) levantan un contenedor real de SQL Server 2022 mediante Testcontainers y corren con Failsafe. Ejecuta las 244 pruebas (209 unitarias/web + 35 de integración contra SQL Server):

mvn verify

También incluye un script end-to-end en `scripts/smoke-test.sh` para probar la API en ejecución contra los archivos de muestra de `samples/`.

## Resumen de la API

Todos los endpoints bajo `/api/v1/**` requieren el encabezado `X-API-Key` (configurado en `.env`).

| Método | Endpoint | Qué hace |
| --- | --- | --- |
| `POST` | `/api/v1/batches` | Sube un archivo `.csv` o `.json` de liquidaciones y encola su procesamiento asíncrono |
| `GET` | `/api/v1/batches/{id}` | Devuelve el estado del lote, fase actual (`INGESTING`, `RECONCILING`, `AUDITING`, `COMPLETED`) y contadores |
| `GET` | `/api/v1/batches/{id}/summary` | Devuelve métricas de conciliación, montos conciliados y conteo de alertas de auditoría |
| `GET` | `/api/v1/batches/{id}/discrepancies` | Lista paginada de diferencias, huérfanos y hallazgos de auditoría (filtrable por estado/severidad) |
| `POST` | `/api/v1/seed` | Genera datos sintéticos de libro mayor y liquidaciones para pruebas locales (deshabilitado en perfil `prod`) |

Los contratos detallados de entrada/salida y códigos de error están en `docs/API.md`.

## Qué hace exactamente

### 1. Ingesta en streaming e idempotencia

Los archivos de liquidación no se cargan completos en memoria. `CsvRecordSource` y `JsonRecordSource` leen los registros por bloques mientras `CountingInputStream` calcula el tamaño en bytes y el checksum SHA-256 al vuelo. Las filas mal formadas no tumban todo el lote: se aíslan en `batch_invalid_records` con su línea y motivo exacto mientras el resto continúa.

### 2. Reglas de conciliación

Cada liquidación externa se evalúa contra el libro mayor interno dentro de la ventana declarada del lote (`SettlementWindow`):

- **Coincidencia exacta (`ExactMatchRule`):** Código de referencia, moneda y monto coinciden.
- **Diferencia de monto (`AmountMismatchRule`):** La referencia existe en el libro mayor, pero el monto liquidado difiere fuera de la tolerancia configurada.
- **Detección de huérfanos (`OrphanDetectionRule`):** Liquidaciones externas sin registro interno, o movimientos internos dentro del alcance que nunca fueron liquidados.
- **Registros tardíos y resolución entre lotes (cross-batch):** Maneja asientos registrados fuera de corte y cierra discrepancias abiertas cuando un lote posterior las liquida (migración `V9`).

### 3. Reglas de auditoría y anomalías

Tras el cruce, `AuditEngine` ejecuta validaciones de cumplimiento y anomalías:

- **Duplicados temporales (`TemporalDuplicateRule`):** Detecta cobros repetidos con características coincidentes dentro de una ventana corta de tiempo.
- **Umbrales corporativos (`CorporateThresholdRule`):** Marca operaciones individuales que superan el límite corporativo configurado.
- **Horarios inusuales (`UnusualTransactionTimeRule`):** Detecta actividad fuera del horario operativo normal (`OperatingHoursPolicy`).

### 4. Recuperación ante fallos y concurrencia

Si la JVM se detiene a mitad de un lote, `BatchRecoveryScheduler` detecta bloqueos expirados y retoma el procesamiento de forma segura sin duplicar resultados, protegido a nivel de base de datos por `BatchOwnershipGuard`.

## Configuración

Los valores base están en `src/main/resources/application.yml`, con perfiles en `application-dev.yml`, `application-prod.yml` y `.env.example`:

| Variable / Propiedad | Propósito |
| --- | --- |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | Conexión JDBC a SQL Server |
| `API_KEY` | Valor esperado en el header `X-API-Key` |
| `reconciliation.processing.chunk-size` | Cantidad de registros procesados por bloque transaccional |
| `reconciliation.matching.amount-tolerance` | Variación decimal permitida para coincidencia exacta |
| `reconciliation.audit.corporate-threshold` | Umbral de alerta para transacciones de alto valor |
| `reconciliation.operating-hours.*` | Ventana horaria para detección de operaciones fuera de horario |

## Estructura del proyecto

enterprise-batch-reconciliation/
├── pom.xml                           # Configuración Maven (Java 21, Spring Boot 3.4.5, Testcontainers 1.21.4)
├── docker-compose.yml                # Contenedor local de SQL Server 2022
├── database/
│   └── 01_create_database.sql        # Script inicial de creación de base de datos
├── docs/
│   ├── API.md                        # Contratos de endpoints y ejemplos
│   ├── ARCHITECTURE.md               # Diseño hexagonal, máquina de estados y concurrencia
│   └── VERIFICATION.md               # Estrategia de pruebas y guía de verificación manual
├── samples/                          # Muestras de Niubiz (CSV), Izipay (JSON) y scripts SQL de ledger
├── scripts/
│   └── smoke-test.sh                 # Prueba rápida end-to-end con curl
└── src/
    ├── main/
    │   ├── java/com/joseantonio/reconciliation/
    │   │   ├── domain/               # Modelos puros (Money, SettlementWindow) y reglas (ReconciliationEngine, AuditEngine)
    │   │   ├── application/          # Casos de uso, puertos de entrada/salida y servicios de orquestación
    │   │   └── infrastructure/       # Controladores REST, seguridad API Key, lectores streaming, adaptadores JPA/JDBC
    │   └── resources/
    │       ├── application*.yml      # Perfiles (default, dev, prod)
    │       └── db/migration/         # Migraciones Flyway para SQL Server (V1 a V9)
    └── test/
        ├── java/.../domain/          # Pruebas unitarias puras de reglas y objetos de valor
        ├── java/.../application/     # Pruebas de pipeline, alcance, recuperación y archivos de muestra
        └── java/.../infrastructure/  # Pruebas web/seguridad + integración con SQL Server Testcontainers (*IT.java)

## Por qué lo diseñé así

Quería construir un motor financiero backend que resolviera los problemas reales que suelen omitirse en proyectos de ejemplo: migraciones reales en SQL Server en lugar de `ddl-auto`, lectores en streaming en lugar de `Files.readAllLines()`, transiciones de estado explícitas con control de propiedad para evitar corrupción concurrente, y pruebas de integración contra un motor SQL Server real en lugar de H2.

| Decisión de ingeniería | Cómo está resuelta |
| --- | --- |
| Aislamiento del dominio | Capa `domain/` en Java puro sin dependencias de Spring ni JPA |
| Eficiencia de memoria en archivos grandes | Lectores streaming CSV/JSON + persistencia por bloques (chunks) |
| Evolución de esquema | 9 scripts versionados de Flyway (`V1`–`V9`) verificados por `MigrationIT` |
| Precisión monetaria y zonas horarias | Objeto de valor `Money` y manejo de UTC/offsets verificado por `TimeZoneIT` |
| Concurrencia y recuperación ante caídas | Control de propiedad en BD + `BatchRecoveryScheduler` verificado por `ConcurrencyAndRecoveryIT` |
| Cobertura de verificación | 244 pruebas automatizadas (209 unitarias/web + 35 ITs con Testcontainers SQL Server) |

Más detalles de diseño en `docs/ARCHITECTURE.esp.md` y `docs/VERIFICATION.esp.md`.