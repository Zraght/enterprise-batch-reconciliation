package com.joseantonio.reconciliation.application.service;

import com.joseantonio.reconciliation.application.port.in.SeedCommand;
import com.joseantonio.reconciliation.application.port.in.SeedLedgerUseCase;
import com.joseantonio.reconciliation.application.port.in.SeedResult;
import com.joseantonio.reconciliation.application.port.in.UploadBatchCommand;
import com.joseantonio.reconciliation.application.port.in.UploadBatchUseCase;
import com.joseantonio.reconciliation.application.port.in.UploadResult;
import com.joseantonio.reconciliation.application.port.out.FileStoragePort;
import com.joseantonio.reconciliation.application.port.out.LedgerPort;
import com.joseantonio.reconciliation.application.port.out.StoredFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Locale;

/** Development/test tool: persists a synthetic ledger and the matching settlement file, optionally submitting it. */
public final class LedgerSeedService implements SeedLedgerUseCase {

    private final SyntheticDataGenerator generator;
    private final LedgerPort ledger;
    private final FileStoragePort storage;
    private final UploadBatchUseCase upload;
    private final Clock clock;
    private final ReconciliationPolicy policy;

    public LedgerSeedService(SyntheticDataGenerator generator, LedgerPort ledger, FileStoragePort storage,
                             UploadBatchUseCase upload, Clock clock, ReconciliationPolicy policy) {
        this.generator = generator;
        this.ledger = ledger;
        this.storage = storage;
        this.upload = upload;
        this.clock = clock;
        this.policy = policy;
    }

    @Override
    public SeedResult seed(SeedCommand command) {
        var data = generator.generate(command, clock.instant(), policy.settlementLag());
        ledger.saveAll(data.ledger());

        byte[] csv = data.settlementCsv().getBytes(StandardCharsets.UTF_8);
        String slug = command.sourceName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        String fileName = "seed-" + slug + "-" + data.randomSeed() + ".csv";

        Long batchId = null;
        String storedName = null;
        if (command.submit()) {
            UploadResult result = upload.upload(new UploadBatchCommand(command.sourceName(), fileName, new ByteArrayInputStream(csv)));
            batchId = result.batch().batchId();
        } else {
            StoredFile stored = storage.store(new ByteArrayInputStream(csv), fileName);
            storedName = stored.location().substring(Math.max(stored.location().lastIndexOf('/'),
                    stored.location().lastIndexOf('\\')) + 1);
        }
        return new SeedResult(command.sourceName(), data.randomSeed(), data.ledger().size(),
                data.settlementRecords(), data.expected(), storedName, batchId);
    }
}
