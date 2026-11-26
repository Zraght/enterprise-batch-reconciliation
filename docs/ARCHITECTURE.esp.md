# Arquitectura y Decisiones de Diseño (v1.1.0)

🌐 [English Version](ARCHITECTURE.md)

## 1. Estructura Hexagonal

El proyecto sigue una arquitectura limpia de Puertos y Adaptadores (*Hexagonal*) para mantener las reglas de negocio completamente desacopladas de Spring, HTTP y SQL Server.

                       ┌──────────────────────────── infrastructure ─────────────────────────────┐
HTTP ──► web (REST) ────┤                                                                          │
  (API-key filter)      │   ┌──────────── application ────────────┐                               │
@Scheduled ─────────────┤   │  port.in  (casos de uso)              │                               │
recovery                │   │  service  (orquestación)              │──► port.out ◄── batch          │
                        │   │                                      │                 (lectores CSV/ │
                        │   │   ┌──────────── domain ──────────┐   │                  JSON, storage, │
                        │   │   │ model · rule (Strategy) · ex.│   │                  dispatcher     │
                        │   │   └──────────────────────────────┘   │                  asíncrono)     │
                        │   └──────────────────────────────────────┘                               │
                        │                                                                          │
                        │   persistence (JPA + JDBC) ─────────────────────────────────► SQL Server │
                        │   config (wiring, propiedades)                                             │
                        └──────────────────────────────────────────────────────────────────────────┘

### Responsabilidades por capa

| Capa | Responsabilidades | Dependencias |
| --- | --- | --- |
| `domain` | Objetos de valor (`Money`, `ReferenceCode`, `SourceName`, `SettlementWindow`), modelos de libro mayor y liquidaciones, estrategias de conciliación y auditoría, y excepciones de negocio. | Únicamente JDK 21 puro |
| `application` | Interfaces de casos de uso (`port.in`), puertos de salida (`port.out`) y servicios de orquestación del flujo. | `domain`, SLF4J |
| `infrastructure` | Controladores REST, DTOs, manejo de errores RFC-7807, filtro de API Key, entidades y repositorios JPA, adaptadores JDBC (`SqlStatements`), lectores streaming y configuración Spring. | `application`, `domain`, Spring Boot |

Ni `domain` ni `application` contienen anotaciones de Spring: toda la inyección de dependencias se configura explícitamente en `infrastructure.config.ApplicationConfig`.

Las entidades JPA residen exclusivamente en `infrastructure.persistence.entity` y nunca se filtran hacia el dominio central.

## 2. Flujo de Procesamiento por Lote

upload ─► guardar archivo (SHA-256 streaming) ─► registrar lote PENDING ─► 202 Accepted
                                                        │
                                                        │ despacho (asíncrono)
                                                        ▼
tryClaim ─► limpiar residuos ─► INGESTION ─► RECONCILIATION ─► AUDIT ─► COMPLETED
(atómico)   (con fencing,       (0–39%)      (40–79%)           (80–99%)   (100%)
             seguro en replay)

                    Cualquier excepción no controlada
                                  │
                                  ▼
                     FAILED (motivo guardado, reclamos liberados)

**Ingesta (0–39%):** Lee el archivo en streaming por bloques acotados (manteniendo un consumo de memoria constante sin importar el tamaño del archivo), valida cada registro, clasifica las referencias repetidas, persiste las filas en transacciones atómicas junto con los contadores del lote y registra las fechas de liquidación mínima y máxima.

**Conciliación (40–79%):** Cruza las liquidaciones externas contra los asientos contables dentro del alcance y clasifica las discrepancias (Sección 3).

**Auditoría (80–99%):** Evalúa reglas de cumplimiento y detección de anomalías sobre los movimientos que participaron en el lote (Sección 5).

## 3. Alcance de Conciliación y Reglas entre Lotes (Cross-Batch)

Un motor real de conciliación no puede marcar indiscriminadamente como faltante cada operación contable que aún no aparece en el archivo del día: los movimientos registrados minutos antes del corte suelen liquidarse en el archivo del día siguiente.

Estas reglas definen el alcance para evitar falsos positivos sin perder visibilidad de las operaciones vencidas.

### Definiciones básicas

| Término | Significado |
| --- | --- |
| Hora de registro (Booking time) | `internal_ledger.tx_timestamp`: momento en que el sistema interno contabilizó la operación. |
| Período de liquidación [inicio, fin] | Ventana temporal que cubre el archivo: declarada por quien sube el lote (`periodStart` + `periodEnd`) o inferida a partir de la marca de tiempo más antigua y más reciente de los registros válidos del archivo. Se almacena en el lote junto con su `periodSource`. |
| Desfase de liquidación (Settlement lag) | Tiempo esperado que tarda la pasarela en liquidar tras el registro contable (`reconciliation.matching.settlement-lag`, por defecto `P1D` para T+1; `PT0S` para el mismo día). |
| Corte de exigibilidad (Due cutoff) | `periodEnd - settlementLag`. Se almacena en cada lote para que sus resultados sigan siendo auditables incluso si la configuración cambia posteriormente. |
| Movimiento dentro del alcance (In-scope ledger entry) | Asiento contable de la misma fuente, en estado `POSTED` y aún no consumido por ningún lote completado. |

### Resultados del cruce

| Escenario | Comportamiento del motor |
| --- | --- |
| La liquidación y el asiento dentro del alcance comparten una referencia | Se emparejan sin importar la diferencia de fechas (una liquidación puede llegar antes o después): se marcan como `MATCHED` si el monto y la moneda coinciden; de lo contrario, como `AMOUNT_MISMATCH` (`CURRENCY_MISMATCH` lleva una diferencia numérica `null`). El asiento queda consumido por el lote. |
| Liquidación sin asiento contable dentro del alcance | Se marca como `MISSING_INTERNAL`. Cuando se conoce el motivo, `detail` lo especifica: `LEDGER_NOT_POSTED` (existe en estado `PENDING` o `REVERSED`) o `ALREADY_RECONCILED` (ya fue conciliado en un lote anterior; es decir, la pasarela reportó dos veces la misma operación en archivos distintos). |
| Asiento dentro del alcance ausente en el archivo, registrado hasta `dueCutoff` | Se marca como `MISSING_EXTERNAL` (vencido). Permanece sin consumir para que un archivo posterior pueda liquidarlo. |
| Asiento dentro del alcance ausente en el archivo, registrado después de `dueCutoff` | Se clasifica como diferido (todavía está dentro del plazo normal de liquidación): no genera discrepancia, permanece sin consumir y se contabiliza en `deferredLedgerEntries`. |
| Asiento en estado `PENDING` o `REVERSED` | Queda fuera del alcance y nunca se reporta como `MISSING_EXTERNAL`. |

### Resolución entre lotes y seguridad en los reintentos

- **Liquidaciones tardías:** Cuando un archivo posterior liquida un asiento marcado previamente como `MISSING_EXTERNAL`, el registro de discrepancia anterior se marca como resuelto (`resolved_by_batch_id`, `resolved_at`). Los resúmenes históricos de los lotes nunca se modifican; en su lugar, `resolvedByLaterBatches` y `openDiscrepancies` reflejan el estado actualizado.
- **Registros contables tardíos (Late bookings):** Si el sistema contable registra una operación después de que un lote completado haya marcado su liquidación como `MISSING_INTERNAL`, el siguiente lote de esa fuente los empareja automáticamente (registrando `LATE_BOOKING` en `detail`), consume el asiento y cierra la discrepancia anterior.
- **Visibilidad continua:** Los movimientos vencidos y aún no liquidados siguen apareciendo en los lotes posteriores de su fuente hasta que se resuelvan, evitando que las operaciones pendientes desaparezcan silenciosamente.
- **Reintentos seguros y rollback ante fallos:** La purga de un lote reintentado vuelve a abrir las discrepancias anteriores que hubiera resuelto. Asimismo, un lote que termina en `FAILED` libera todos los reclamos y resoluciones contables para evitar que bloquee procesamientos posteriores.

### Manejo de referencias repetidas

| Ubicación | Regla | Justificación |
| --- | --- | --- |
| Libro mayor interno | Como máximo un registro `POSTED` por `(source, reference)` mediante un índice único filtrado. Las filas `PENDING` y `REVERSED` sí pueden compartir la referencia. | Una reversión comparte legítimamente la referencia de la operación original; limitar la unicidad a `POSTED` evita falsos conflictos y garantiza un emparejamiento inequívoco. |
| Archivo de liquidación | Se concilia la primera aparición; las repeticiones posteriores dentro del mismo archivo se rechazan como `DUPLICATE_REFERENCE_IDENTICAL` (repetición exacta) o `DUPLICATE_REFERENCE_CONFLICT` (diferencias en monto, moneda o fecha). | Elegir silenciosamente una de dos líneas contradictorias ocultaría problemas de calidad de datos. |

## 4. Precisión Monetaria y Consistencia de Zona Horaria

**Aritmética exacta:** Todos los montos utilizan `BigDecimal` en Java y `DECIMAL(19,4)` en SQL Server. Nunca se utilizan tipos de punto flotante (`float`/`double`) para representar dinero (verificado por `MigrationIT`).

**Validación estricta de escala:** `Money` exige `amount > 0`, hasta 15 dígitos enteros y un máximo de 4 decimales. Los decimales adicionales se rechazan en lugar de redondearse.

El redondeo `HALF_EVEN` se aplica exclusivamente a estadísticas derivadas (como `reconciliationRatePercent`).

La validación de rango se ejecuta antes de las operaciones de escala para evitar el agotamiento de memoria ante notaciones científicas malformadas o maliciosas (por ejemplo, `1E+999999999`).

**Fórmula de discrepancia:** `discrepancyAmount = settlementAmount - ledgerAmount` (considerando 0 cuando falta uno de los lados, o `null` cuando las monedas no coinciden). Los totales se agregan de forma independiente por moneda.

**Consistencia de zona horaria:** Los timestamps del dominio utilizan `Instant`; los archivos exigen offsets ISO-8601 explícitos (`Z` o `±HH:MM`); SQL Server almacena valores UTC en `DATETIME2(3)`, truncados a milisegundos.

Al iniciar, `ReconciliationApplication` fija la zona horaria predeterminada de la JVM en UTC (y omite `hibernate.jdbc.time_zone=UTC`) para que las entidades JPA y las instrucciones JDBC directas nunca presenten diferencias debido al offset del sistema operativo anfitrión (verificado por `TimeZoneIT`).

La hora local solo se utiliza dentro de `OperatingHoursPolicy` mediante un `ZoneId` explícito.

## 5. Motor de Auditoría Histórica

Después del emparejamiento, `AuditEngine` evalúa reglas configurables bajo el patrón Strategy (`TemporalDuplicateRule`, `CorporateThresholdRule`, `UnusualTransactionTimeRule`) sobre los movimientos involucrados en el lote:

- **Historial entre lotes y fuentes:** Los movimientos se agrupan por cuenta (hasta 500 cuentas por consulta). El motor consulta los asientos contables `POSTED` dentro de la ventana de retrospectiva configurada (30 segundos por defecto), considerando todos los lotes, fuentes y estados de conciliación. Únicamente se marca la transacción posterior de un par duplicado, detectando duplicados incluso cuando se encuentran distribuidos entre dos lotes o dos pasarelas de pago diferentes.
- **Deduplicación permanente de alertas:** El almacenamiento garantiza una única alerta por `(ledger_tx_id, rule_code)` mediante la restricción única `UX_aa_ledger_rule`, evitando que un movimiento evaluado nuevamente durante una resolución posterior entre lotes genere alertas duplicadas.
- **Límite del alcance:** Los movimientos diferidos se auditan cuando participan en un lote (emparejados, con diferencias o reportados como vencidos).

## 6. Concurrencia, Fencing e Integridad de Datos

| Escenario de concurrencia / integridad | Mecanismo |
| --- | --- |
| Dos workers reclaman el mismo lote | Una única sentencia atómica `UPDATE ... SET status='PROCESSING', attempt_no=attempt_no+1 ... WHERE status='PENDING' OUTPUT ...` garantiza que solo un worker obtenga el bloqueo. |
| Lotes simultáneos de la misma fuente | El índice único filtrado `UX_rb_single_processing_per_source` impide que dos lotes de la misma fuente compitan por los mismos asientos contables; el segundo permanece en `PENDING` para el planificador. |
| Un worker obsoleto se reactiva después de perder su turno | **Token de fencing (`attempt_no`):** Cada transacción de bloque comienza con `UPDATE ... WHERE status='PROCESSING' AND attempt_no=:mine`, adquiriendo un bloqueo exclusivo sobre la fila del lote y renovando su heartbeat. Si afecta a cero filas, lanza `BatchOwnershipLostException` y revierte inmediatamente la transacción. |
| Purga protegida durante los reintentos | Cada paso de purga se ejecuta en una transacción corta que primero bloquea la fila del lote y valida `attempt_no`; luego elimina como máximo 2,000 filas. Un worker obsoleto falla antes de ejecutar cualquier `DELETE`, y el límite de 2,000 filas evita la escalada de bloqueos a nivel de tabla en SQL Server. |
| Liberación de marcas contables ante un fallo | Se ejecuta en transacciones acotadas bloqueadas bajo `status='FAILED' AND attempt_no=:mine`, de modo que un reintento concurrente detiene inmediatamente la limpieza obsoleta. |
| Doble consumo de un asiento contable | Actualización condicional compare-and-set: `UPDATE internal_ledger SET reconciled_batch_id=? WHERE tx_id=? AND reconciled_batch_id IS NULL`; si afecta a cero filas, se aborta y revierte el bloque. |
| Cargas simultáneas del mismo archivo | Restricción única sobre `(source_name, file_sha256)`; el hilo que pierde la carrera captura la violación de la restricción y devuelve de forma idempotente el identificador del lote existente. |

**Aislamiento y pool de conexiones:** Opera bajo `READ COMMITTED`, apoyándose en actualizaciones condicionales atómicas e índices únicos en lugar de bloqueos pesados.

Como la fase de auditoría mantiene un cursor abierto mientras consulta el historial e inserta alertas mediante conexiones secundarias, cada lote activo utiliza hasta 2 conexiones (tamaño del pool `>= 2 * max-concurrent-batches + 4`).

## 7. Recuperación ante Fallos

- Cada bloque procesado actualiza un heartbeat utilizando el reloj de la base de datos en la fila del lote.
- `BatchRecoveryScheduler` se ejecuta al iniciar la aplicación y cada 30 segundos posteriormente, liberando los lotes en estado `PROCESSING` cuyo heartbeat supere `stale-after` y volviendo a despachar los lotes en estado `PENDING`.
- Cuando un nuevo worker toma un lote recuperado, elimina cualquier resultado parcial derivado del intento interrumpido y vuelve a procesarlo limpiamente desde el archivo almacenado.
- Los errores estructurales del archivo cambian el estado del lote a `FAILED` con un código de motivo sanitizado; volver a cargar el mismo archivo permite reencolarlo de forma segura.

## 8. Seguridad y Endurecimiento de la API

| Medida | Implementación |
| --- | --- |
| Filtro de API Key | Secreto compartido mediante `X-API-Key` (`API_KEY`), con política `deny-by-default` en todas las rutas, excepto `/actuator/health`, Swagger UI y `/v3/api-docs`. Utiliza comparación de tiempo constante mediante hashes SHA-256 sobre la ruta de solicitud normalizada por el contenedor (bloqueando intentos de evasión mediante manipulación de rutas como `/swagger-ui/../api/...`). |
| Arranque seguro en producción | En el perfil `prod`, `api-key-required=true` detiene inmediatamente el arranque si falta `API_KEY` o tiene menos de 16 caracteres. Las claves vacías solo se permiten en entornos que no sean de producción, acompañadas de una advertencia durante el arranque. |
| Perfiles explícitos | No existe un perfil activo por defecto. `SeedController` solo se registra cuando se activa explícitamente `dev` o `test` (`SPRING_PROFILES_ACTIVE=dev`). El perfil `prod` deshabilita Swagger/OpenAPI por defecto y oculta los detalles de salud. |
| Endurecimiento de entradas y rutas | `sourceName` está restringido a caracteres seguros para identificadores. Los nombres de archivo cargados se reducen a su segmento final de ruta, se eliminan los caracteres de control, se limitan a 255 caracteres y se almacenan utilizando nombres UUID generados por el servidor. Se rechazan las propiedades JSON desconocidas. |
| Respuestas de error sanitizadas | Los errores siguen RFC 7807 (`application/problem+json`) con códigos de error estables de la aplicación. Nunca se exponen stack traces, consultas SQL, nombres de host ni rutas locales del sistema de archivos en las respuestas de la API o en los motivos de fallo almacenados en la base de datos. |
| Encabezados de seguridad | Las respuestas bajo `/api/**` incluyen `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer` y `Cache-Control: no-store`. |

**Nota sobre el alcance:** El filtro por API Key está diseñado para comunicación interna entre servicios o despliegues detrás de un API Gateway. Para implementar control de acceso multiusuario (RBAC), rotación de tokens o trazabilidad de los clientes que realizan las solicitudes, se debe colocar un gateway OAuth2/OIDC delante del servicio.

## 9. Decisiones sobre Tecnologías

**Servicios propios en lugar de Spring Batch:** Spring Batch introduce sus propias tablas de metadatos `BATCH_*` y una semántica de reinicio que entra en conflicto con el fencing por fuente y la resolución contable entre lotes. Los servicios personalizados por bloques mantienen limpio el esquema (V1–V9) y permiten pruebas unitarias rápidas en memoria sin levantar un contexto de Spring.

**Virtual Threads acotados:** `AsyncBatchDispatcher` utiliza hilos virtuales de Java 21 limitados mediante un `Semaphore` (`max-concurrent-batches`) en un ejecutor privado (evitando sobrescribir el `applicationTaskExecutor` predeterminado de Spring Boot). Configurar `virtual-threads=false` cambia el mecanismo a un pool tradicional de hilos fijos.

**Persistencia híbrida JPA + JDBC:** Spring Data JPA gestiona las entidades del ciclo de vida de los lotes y las consultas dinámicas paginadas de discrepancias, mientras que `JdbcTemplate` (`SqlStatements`) ejecuta inserciones masivas de alto rendimiento (evitando la limitación del batching con `IDENTITY` de Hibernate en SQL Server) y transiciones atómicas de estado.

La definición DDL del esquema pertenece exclusivamente a Flyway (`ddl-auto=none`).

## 10. Límites Operativos Conocidos

- **Consumo de memoria por lote:** Mantiene un bloque en procesamiento y un conjunto en memoria de códigos de referencia vistos para detectar duplicados (~100 MB por cada millón de registros en un mismo archivo).
- **Agrupación por cuenta en auditoría:** Carga en memoria los movimientos de una cuenta dentro del lote durante la evaluación de auditoría. Una sola cuenta que genere millones de transacciones dentro de un único lote queda fuera del perfil de carga previsto.
- **Almacenamiento local de archivos:** Los archivos cargados se almacenan en el nodo que recibe la solicitud HTTP. Para despliegues horizontales multinodo, debe implementarse un adaptador de almacenamiento compartido para `FileStoragePort` (por ejemplo, S3 o Azure Blob Storage).
- **Un único desfase de liquidación por despliegue:** `settlement-lag` se configura globalmente por despliegue y no de manera individual para cada pasarela de pago.

**Ubicación final de tus archivos:**

docs/
├── ARCHITECTURE.md
└── ARCHITECTURE.esp.md