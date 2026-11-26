🌐 [English version](PROJECT_STATUS.md)

# Estado del Proyecto — v1.1.0

Estado actual de ingeniería y verificación de **Enterprise Batch Reconciliation & Audit Engine**. Todo lo detallado a continuación ha sido compilado y ejecutado localmente sobre JDK 21 y un contenedor real de SQL Server 2022.

---

## Resumen de Verificación

| Suite | Ejecutor | Alcance | Resultado |
|---|---|---|---|
| Unitarias y Web (`*Test.java`) | Maven Surefire | Reglas de dominio, flujo de aplicación, lectores streaming, controladores REST y seguridad API Key | **209 / 209 pasaron** |
| Integración (`*IT.java`) | Maven Failsafe + Testcontainers 1.21.4 | SQL Server 2022 real (`mcr.microsoft.com/mssql/server:2022-latest`) + migraciones Flyway `V1`–`V9` | **35 / 35 pasaron** |
| **Total** | `mvn verify` | Ciclo completo de compilación y pruebas | **244 / 244 pasaron (`BUILD SUCCESS`)** |

---

## Capacidades Implementadas y Verificadas

### 1. Concurrencia, Fencing y Recuperación ante Caídas
- La limpieza de datos derivados (`purgeDerivedData`) opera en transacciones acotadas (`<= 2,000` filas por borrado) protegidas por `BatchOwnershipGuard`, impidiendo que un worker obsoleto modifique o elimine registros tras perder el *lease* del lote.
- Los lotes fallidos liberan las entradas reclamadas del libro mayor y reabren discrepancias resueltas previamente.
- Verificado en memoria (`RecoveryAndFailureTest`) y contra bloqueos reales en SQL Server (`ConcurrencyAndRecoveryIT`).

### 2. Alcance de Conciliación y Resolución entre Lotes (*Cross-Batch*)
- Aplica ventanas de liquidación declaradas o inferidas, desfase configurable (`P1D`), horas de corte (*due cutoff*), movimientos diferidos y filtrado de estados `PENDING` / `REVERSED`.
- Clasifica referencias externas repetidas (duplicados idénticos frente a conflictivos) y resuelve discrepancias entre lotes cuando una liquidación o asiento contable llega tardíamente (`ALREADY_RECONCILED`).
- Verificado en `ReconciliationScopeTest`, `SampleFilesTest` y `ScopeAndCrossBatchIT`.

### 3. Auditoría Histórica y Detección de Anomalías
- Evalúa el historial entre lotes y fuentes para detectar duplicados temporales, operaciones por encima del umbral corporativo y transacciones fuera de horario operativo (`OperatingHoursPolicy`).
- Garantiza identidad determinista de alertas (`una alerta por movimiento + regla`) tanto en dominio como mediante restricciones en base de datos (migración `V9`).
- Verificado en `AuditRulesTest`, `AuditHistoryTest` y `ReconciliationFlowIT`.

### 4. Integridad de Esquema y Consistencia de Zona Horaria
- 9 migraciones versionadas con Flyway (`V1`–`V9`) que cubren lotes, unicidad en libro mayor (`POSTED`), liquidaciones externas, resultados, alertas de auditoría, cuarentena de registros inválidos y resolución entre lotes.
- La zona horaria de la JVM se fija a UTC al iniciar (`ReconciliationApplication`) para mantener consistencia total entre JPA y JDBC sin importar la zona del servidor anfitrión (probado en `America/Lima` y `UTC`).
- Verificado por `MigrationIT` y `TimeZoneIT`.

### 5. Seguridad de la API y Endurecimiento Operativo
- Verificación en tiempo constante de `X-API-Key` (`ApiKeyVerifier`, `ApiKeyFilter`) con política *deny-by-default*, normalización de rutas y validación obligatoria en perfil `prod`.
- El endpoint de carga sintética (`SeedController`) queda deshabilitado automáticamente en `prod`.
- Los lectores de archivos sanitizan mensajes de error para no exponer rutas internas del servidor en respuestas ni registros de fallo.
- Verificado por `ApiKeyFilterTest`, `SeedControllerDisabledInProdTest`, `SeedControllerEnabledTest` y `ApiKeySecurityIT`.

---

## Notas de Entorno y Compilación

- **Runtime y Build:** Java 21 (Eclipse Temurin), Spring Boot 3.4.5, Maven 3.9.9.
- **Compatibilidad Testcontainers:** Se fijó `<testcontainers.version>1.21.4</testcontainers.version>` en `pom.xml` para negociar correctamente la API moderna de Docker Engine (Docker Desktop 4.100+ / Engine 29+ sobre WSL2/Windows npipe).

---

## Próximos Pasos (Fuera del alcance v1.1.0)

1. **Autenticación Multi-usuario:** Sustituir las API keys compartidas por OAuth2 / OIDC en el API Gateway para control de acceso por roles.
2. **Almacenamiento Distribuido:** Implementar un adaptador de `FileStoragePort` sobre S3 o Azure Blob Storage para escalado horizontal multi-nodo sin disco local compartido.
3. **Pipeline CI/CD:** Configurar un flujo en GitHub Actions que ejecute `mvn verify` automáticamente en cada Pull Request.