package com.joseantonio.reconciliation.infrastructure.config;

import com.joseantonio.reconciliation.application.port.in.ProcessBatchUseCase;
import com.joseantonio.reconciliation.application.port.in.QueryBatchUseCase;
import com.joseantonio.reconciliation.application.port.in.RecoverBatchesUseCase;
import com.joseantonio.reconciliation.application.port.in.SeedLedgerUseCase;
import com.joseantonio.reconciliation.application.port.in.UploadBatchUseCase;
import com.joseantonio.reconciliation.application.port.out.AuditAlertPort;
import com.joseantonio.reconciliation.application.port.out.BatchDispatchPort;
import com.joseantonio.reconciliation.application.port.out.BatchRepositoryPort;
import com.joseantonio.reconciliation.application.port.out.FileStoragePort;
import com.joseantonio.reconciliation.application.port.out.LedgerPort;
import com.joseantonio.reconciliation.application.port.out.ReconciliationResultPort;
import com.joseantonio.reconciliation.application.port.out.SettlementFileReaderPort;
import com.joseantonio.reconciliation.application.port.out.SettlementStorePort;
import com.joseantonio.reconciliation.application.service.AuditService;
import com.joseantonio.reconciliation.application.service.BatchProcessingService;
import com.joseantonio.reconciliation.application.service.BatchQueryService;
import com.joseantonio.reconciliation.application.service.BatchRecoveryService;
import com.joseantonio.reconciliation.application.service.BatchRegistrationService;
import com.joseantonio.reconciliation.application.service.LedgerSeedService;
import com.joseantonio.reconciliation.application.service.ProcessingSettings;
import com.joseantonio.reconciliation.application.service.ReconciliationPolicy;
import com.joseantonio.reconciliation.application.service.ReconciliationService;
import com.joseantonio.reconciliation.application.service.SettlementIngestionService;
import com.joseantonio.reconciliation.application.service.SettlementRecordMapper;
import com.joseantonio.reconciliation.application.service.SyntheticDataGenerator;
import com.joseantonio.reconciliation.domain.model.AccountType;
import com.joseantonio.reconciliation.domain.rule.AuditEngine;
import com.joseantonio.reconciliation.domain.rule.CorporateThresholdRule;
import com.joseantonio.reconciliation.domain.rule.OperatingHoursPolicy;
import com.joseantonio.reconciliation.domain.rule.ReconciliationEngine;
import com.joseantonio.reconciliation.domain.rule.TemporalDuplicateRule;
import com.joseantonio.reconciliation.domain.rule.ThresholdPolicy;
import com.joseantonio.reconciliation.domain.rule.UnusualTransactionTimeRule;
import com.joseantonio.reconciliation.infrastructure.batch.AsyncBatchDispatcher;
import com.joseantonio.reconciliation.infrastructure.batch.LocalFileStorageAdapter;
import com.joseantonio.reconciliation.infrastructure.batch.SettlementFileReaderAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Currency;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Composition root. Domain and application classes carry no Spring annotations (they stay framework-free and are
 * unit-tested without a container); this class wires them to the infrastructure adapters.
 */
@Configuration
@EnableScheduling
public class ApplicationConfig {

    // ---------------------------------------------------------------- infrastructure basics

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public ProcessingSettings processingSettings(ReconciliationProperties props) {
        var p = props.processing();
        return new ProcessingSettings(p.chunkSize(), p.maxInvalidRatio(), p.staleAfter(), p.recoveryLimit());
    }

    @Bean
    public ReconciliationPolicy reconciliationPolicy(ReconciliationProperties props) {
        return new ReconciliationPolicy(props.matching().settlementLag());
    }

    @Bean
    public FileStoragePort fileStoragePort(ReconciliationProperties props) {
        return new LocalFileStorageAdapter(Path.of(props.storage().directory()));
    }

    @Bean
    public SettlementFileReaderPort settlementFileReaderPort() {
        return new SettlementFileReaderAdapter();
    }

    /**
     * Asynchronous batch runner. Batches are long-running and I/O bound (file + JDBC), few in number and capped by a
     * semaphore: virtual threads give cheap blocking without sizing a pool. They can be switched off
     * ({@code reconciliation.processing.virtual-threads=false}) for a classic fixed pool.
     * <p>The executor is created here and owned by the dispatcher (closed with it) instead of being exposed as a bean:
     * a public {@code Executor} bean would make Spring Boot back off its own {@code applicationTaskExecutor}.</p>
     */
    @Bean(destroyMethod = "close")
    public AsyncBatchDispatcher batchDispatcher(ReconciliationProperties props, ProcessBatchUseCase processBatchUseCase) {
        var p = props.processing();
        ExecutorService executor = p.virtualThreads()
                ? Executors.newVirtualThreadPerTaskExecutor()
                : Executors.newFixedThreadPool(p.maxConcurrentBatches());
        return new AsyncBatchDispatcher(executor, p.maxConcurrentBatches(), processBatchUseCase);
    }

    // ---------------------------------------------------------------- domain rule engines

    @Bean
    public ReconciliationEngine reconciliationEngine() {
        return ReconciliationEngine.standard();
    }

    @Bean
    public AuditEngine auditEngine(ReconciliationProperties props) {
        var audit = props.audit();
        Map<AccountType, Map<Currency, BigDecimal>> limits = new EnumMap<>(AccountType.class);
        audit.thresholds().forEach((type, perCurrency) ->
                limits.put(AccountType.valueOf(type.trim().toUpperCase(Locale.ROOT)), toCurrencyMap(perCurrency)));

        var hours = audit.operatingHours();
        var operatingHours = new OperatingHoursPolicy(ZoneId.of(hours.zone()),
                LocalTime.parse(hours.opensAt()), LocalTime.parse(hours.closesAt()), toCurrencyMap(hours.highValue()));

        return new AuditEngine(List.of(
                new TemporalDuplicateRule(audit.duplicateWindow()),
                new CorporateThresholdRule(new ThresholdPolicy(limits)),
                new UnusualTransactionTimeRule(operatingHours)));
    }

    private static Map<Currency, BigDecimal> toCurrencyMap(Map<String, BigDecimal> byCode) {
        Map<Currency, BigDecimal> result = new HashMap<>();
        byCode.forEach((code, value) -> result.put(Currency.getInstance(code.trim().toUpperCase(Locale.ROOT)), value));
        return result;
    }

    // ---------------------------------------------------------------- application services (use cases)

    @Bean
    public SettlementRecordMapper settlementRecordMapper() {
        return new SettlementRecordMapper();
    }

    @Bean
    public SettlementIngestionService settlementIngestionService(SettlementFileReaderPort reader, SettlementStorePort store,
                                                                 SettlementRecordMapper mapper, ProcessingSettings settings) {
        return new SettlementIngestionService(reader, store, mapper, settings);
    }

    @Bean
    public ReconciliationService reconciliationService(ReconciliationEngine engine, SettlementStorePort settlements,
                                                       LedgerPort ledger, ReconciliationResultPort results,
                                                       BatchRepositoryPort batches, ProcessingSettings settings,
                                                       ReconciliationPolicy policy) {
        return new ReconciliationService(engine, settlements, ledger, results, batches, settings, policy);
    }

    @Bean
    public AuditService auditService(AuditEngine engine, LedgerPort ledger, AuditAlertPort alerts,
                                     BatchRepositoryPort batches, ProcessingSettings settings, Clock clock) {
        return new AuditService(engine, ledger, alerts, batches, settings, clock);
    }

    @Bean
    public ProcessBatchUseCase processBatchUseCase(BatchRepositoryPort batches, SettlementIngestionService ingestion,
                                                   ReconciliationService reconciliation, AuditService audit) {
        return new BatchProcessingService(batches, ingestion, reconciliation, audit);
    }

    @Bean
    public UploadBatchUseCase uploadBatchUseCase(FileStoragePort storage, BatchRepositoryPort batches,
                                                 BatchDispatchPort dispatcher, Clock clock) {
        return new BatchRegistrationService(storage, batches, dispatcher, clock);
    }

    @Bean
    public QueryBatchUseCase queryBatchUseCase(BatchRepositoryPort batches, ReconciliationResultPort results,
                                               AuditAlertPort alerts, SettlementStorePort settlements) {
        return new BatchQueryService(batches, results, alerts, settlements);
    }

    @Bean
    public RecoverBatchesUseCase recoverBatchesUseCase(BatchRepositoryPort batches, BatchDispatchPort dispatcher,
                                                       ProcessingSettings settings) {
        return new BatchRecoveryService(batches, dispatcher, settings);
    }

    @Bean
    public SeedLedgerUseCase seedLedgerUseCase(LedgerPort ledger, FileStoragePort storage,
                                               UploadBatchUseCase upload, Clock clock, ReconciliationPolicy policy) {
        return new LedgerSeedService(new SyntheticDataGenerator(), ledger, storage, upload, clock, policy);
    }
}
