package com.joseantonio.reconciliation.application;

import com.joseantonio.reconciliation.application.port.in.SeedCommand;
import com.joseantonio.reconciliation.application.port.in.SeedResult;
import com.joseantonio.reconciliation.application.service.ProcessingSettings;
import com.joseantonio.reconciliation.application.service.SyntheticDataGenerator;
import com.joseantonio.reconciliation.domain.exception.InvalidRequestException;
import com.joseantonio.reconciliation.domain.model.BatchStatus;
import com.joseantonio.reconciliation.domain.model.MatchStatus;
import com.joseantonio.reconciliation.testsupport.Harness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyntheticDataPipelineTest {

    @TempDir
    Path dir;

    private static final Instant NOW = Instant.parse("2026-03-11T00:00:00Z");

    @Test
    void sameSeedProducesIdenticalDataAndDifferentSeedDoesNot() {
        var gen = new SyntheticDataGenerator();
        var cmd = SeedCommand.defaults("NIUBIZ", 500, 42L, false);
        var a = gen.generate(cmd, NOW, Duration.ofDays(1));
        var b = gen.generate(cmd, NOW, Duration.ofDays(1));
        assertEquals(a.settlementCsv(), b.settlementCsv());
        assertEquals(a.ledger(), b.ledger());
        assertEquals(a.expected(), b.expected());
        assertTrue(!a.settlementCsv().equals(gen.generate(SeedCommand.defaults("NIUBIZ", 500, 43L, false), NOW, Duration.ofDays(1)).settlementCsv()));
    }

    @Test
    void generatedDataContainsEveryScenarioAndNoFloatingPointArtifacts() {
        var data = new SyntheticDataGenerator().generate(SeedCommand.defaults("NIUBIZ", 2000, 7L, false), NOW, Duration.ofDays(1));
        var e = data.expected();
        assertTrue(e.matched() > 0 && e.amountMismatch() > 0 && e.missingExternal() > 0
                && e.missingInternal() > 0 && e.invalidRecords() > 0 && e.minTemporalDuplicateAlerts() > 0);
        data.ledger().forEach(l -> assertEquals(4, l.amount().amount().scale()));
        assertTrue(!data.settlementCsv().contains("E+") && !data.settlementCsv().contains("NaN"));
    }

    @Test
    void theEngineFindsExactlyWhatTheGeneratorInjected() {
        Harness h = new Harness(dir, new ProcessingSettings(250, new BigDecimal("0.5"), Duration.ofMinutes(5), 100));
        SeedResult seed = h.seeding.seed(SeedCommand.defaults("NIUBIZ", 3000, 2026L, true));
        long id = seed.batchId();
        assertNotNull(id);
        assertEquals(3000 + seed.expected().minTemporalDuplicateAlerts(), seed.ledgerEntriesCreated());

        h.processing.process(id);
        var row = h.world.row(id);
        assertEquals(BatchStatus.COMPLETED, row.status, row.failure);

        var counts = h.world.countByStatus(id);
        var exp = seed.expected();
        assertEquals(exp.matched(), counts.getOrDefault(MatchStatus.MATCHED, 0L));
        assertEquals(exp.amountMismatch(), counts.getOrDefault(MatchStatus.AMOUNT_MISMATCH, 0L));
        assertEquals(exp.missingExternal(), counts.getOrDefault(MatchStatus.MISSING_EXTERNAL, 0L));
        assertEquals(exp.missingInternal(), counts.getOrDefault(MatchStatus.MISSING_INTERNAL, 0L));
        assertEquals(exp.invalidRecords(), row.invalid);
        assertEquals(seed.settlementRecordsGenerated(), row.total);

        long duplicateAlerts = h.world.alerts(id).stream().filter(a -> a.rule().equals("TEMPORAL_DUPLICATE")).count();
        assertTrue(duplicateAlerts >= exp.minTemporalDuplicateAlerts(),
                duplicateAlerts + " duplicate alerts, expected at least " + exp.minTemporalDuplicateAlerts());
        var summary = h.query.getSummary(id);
        assertEquals(exp.matched() + exp.amountMismatch() + exp.missingExternal() + exp.missingInternal(), summary.evaluatedReferences());
    }

    @Test
    void seedWithoutSubmitStoresTheFileAndDoesNotCreateABatch() throws Exception {
        Harness h = new Harness(dir);
        SeedResult r = h.seeding.seed(SeedCommand.defaults("IZIPAY", 50, 1L, false));
        assertNull(r.batchId());
        assertTrue(Files.size(dir.resolve(r.settlementFileName())) > 0);
        assertEquals(0, h.world.findPendingBatchIds(10).size());
    }

    @Test
    void seedingTheSameDataTwiceIsRejectedByTheUniqueConstraintNotSilentlyDuplicated() {
        Harness h = new Harness(dir);
        h.seeding.seed(SeedCommand.defaults("NIUBIZ", 20, 5L, false));
        assertThrows(IllegalStateException.class, () -> h.seeding.seed(SeedCommand.defaults("NIUBIZ", 20, 5L, false)));
        assertEquals(20, h.world.ledgerSize(), "the rejected second seed must not leave partial data");
    }

    @Test
    void seedCommandValidatesItsInputs() {
        assertThrows(InvalidRequestException.class, () -> SeedCommand.defaults(" ", 10, 1L, false));
        assertThrows(InvalidRequestException.class, () -> SeedCommand.defaults("X", 0, 1L, false));
        assertThrows(InvalidRequestException.class, () -> SeedCommand.defaults("X", SeedCommand.MAX_COUNT + 1, 1L, false));
        assertThrows(InvalidRequestException.class, () -> new SeedCommand("X", 10, 1L, 0.6, 0, 0, 0, 0, 0, false));
        assertThrows(InvalidRequestException.class, () -> new SeedCommand("X", 10, 1L, 0.5, 0.5, 0, 0, 0, 0, false));
        assertThrows(InvalidRequestException.class, () -> new SeedCommand("X", 10, 1L, Double.NaN, 0, 0, 0, 0, 0, false));
    }
}
