package com.joseantonio.reconciliation.testsupport;

import com.joseantonio.reconciliation.application.port.in.UploadBatchCommand;
import com.joseantonio.reconciliation.application.port.in.UploadResult;
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
import com.joseantonio.reconciliation.domain.model.LedgerEntry;
import com.joseantonio.reconciliation.domain.rule.AuditEngine;
import com.joseantonio.reconciliation.domain.rule.CorporateThresholdRule;
import com.joseantonio.reconciliation.domain.rule.OperatingHoursPolicy;
import com.joseantonio.reconciliation.domain.rule.ReconciliationEngine;
import com.joseantonio.reconciliation.domain.rule.TemporalDuplicateRule;
import com.joseantonio.reconciliation.domain.rule.ThresholdPolicy;
import com.joseantonio.reconciliation.domain.rule.UnusualTransactionTimeRule;
import com.joseantonio.reconciliation.infrastructure.batch.LocalFileStorageAdapter;
import com.joseantonio.reconciliation.infrastructure.batch.SettlementFileReaderAdapter;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.Map;

/** Wires the real application services on top of the in-memory persistence and the real file adapters. */
public final class Harness {

    public final MutableClock clock = new MutableClock(Instant.parse("2026-03-11T00:00:00Z"));
    public final InMemoryWorld world = new InMemoryWorld(clock);
    public final ProcessingSettings settings;
    public final ReconciliationPolicy policy;
    public final LocalFileStorageAdapter storage;
    public final BatchRegistrationService registration;
    public final BatchProcessingService processing;
    public final BatchRecoveryService recovery;
    public final BatchQueryService query;
    public final LedgerSeedService seeding;
    public final SettlementIngestionService ingestion;
    public final ReconciliationService reconciliation;
    public final AuditService audit;

    public Harness(Path storageDir, ProcessingSettings settings) {
        this(storageDir, settings, new ReconciliationPolicy(Duration.ZERO));
    }

    public Harness(Path storageDir, ProcessingSettings settings, ReconciliationPolicy policy) {
        this.settings = settings;
        this.policy = policy;
        this.storage = new LocalFileStorageAdapter(storageDir);
        this.registration = new BatchRegistrationService(storage, world, world, clock);
        this.ingestion = new SettlementIngestionService(new SettlementFileReaderAdapter(), world,
                new SettlementRecordMapper(), settings);
        this.reconciliation = new ReconciliationService(ReconciliationEngine.standard(), world, world, world, world, settings, policy);
        this.audit = new AuditService(auditEngine(), world, world, world, settings, clock);
        this.processing = new BatchProcessingService(world, ingestion, reconciliation, audit);
        this.recovery = new BatchRecoveryService(world, world, settings);
        this.query = new BatchQueryService(world, world, world, world);
        this.seeding = new LedgerSeedService(new SyntheticDataGenerator(), world, storage, registration, clock, policy);
    }

    public Harness(Path storageDir) {
        this(storageDir, new ProcessingSettings(3, new BigDecimal("0.5"), Duration.ofMinutes(5), 100));
    }

    public Harness(Path storageDir, ReconciliationPolicy policy) {
        this(storageDir, new ProcessingSettings(3, new BigDecimal("0.5"), Duration.ofMinutes(5), 100), policy);
    }

    public static AuditEngine auditEngine() {
        Currency pen = Currency.getInstance("PEN");
        return new AuditEngine(List.of(
                new TemporalDuplicateRule(Duration.ofSeconds(30)),
                new CorporateThresholdRule(new ThresholdPolicy(Map.of(
                        AccountType.RETAIL, Map.of(pen, new BigDecimal("10000")),
                        AccountType.BUSINESS, Map.of(pen, new BigDecimal("100000")),
                        AccountType.CORPORATE, Map.of(pen, new BigDecimal("500000"))))),
                new UnusualTransactionTimeRule(new OperatingHoursPolicy(ZoneId.of("America/Lima"),
                        LocalTime.of(6, 0), LocalTime.of(22, 0), Map.of(pen, new BigDecimal("5000"))))));
    }

    public void ledger(LedgerEntry... entries) {
        world.saveAll(List.of(entries));
    }

    public UploadResult upload(String source, String fileName, String content) {
        return registration.upload(new UploadBatchCommand(source, fileName,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))));
    }

    /** Uploads and processes synchronously; returns the batch id. */
    public long uploadAndProcess(String source, String fileName, String content) {
        long id = upload(source, fileName, content).batch().batchId();
        processing.process(id);
        return id;
    }
}
