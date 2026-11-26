package com.joseantonio.reconciliation.infrastructure.batch;

import com.joseantonio.reconciliation.application.port.out.RawRecord;
import com.joseantonio.reconciliation.application.port.out.RecordSource;
import com.joseantonio.reconciliation.application.service.SettlementRecordMapper;
import com.joseantonio.reconciliation.domain.exception.FileProcessingException;
import com.joseantonio.reconciliation.domain.model.FileFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileReadersTest {

    @TempDir
    Path dir;

    private final SettlementFileReaderAdapter reader = new SettlementFileReaderAdapter();

    private Path write(String name, String content) throws IOException {
        return Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    private List<RawRecord> readAll(Path file, FileFormat format) {
        List<RawRecord> out = new ArrayList<>();
        try (RecordSource source = reader.open(file.toString(), format)) {
            RawRecord r;
            while ((r = source.next()) != null) {
                out.add(r);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ CSV

    @Test
    void csvIsReadWithNormalisedHeaderAndTrimmedValues() throws IOException {
        Path f = write("a.csv", "External_Ref , Amount,CURRENCY,settlement_timestamp\n"
                + " r-1 , 10.50 ,pen,2026-03-10T15:00:00Z\n"
                + "r-2,20,usd,2026-03-10T15:00:01Z\n");
        try (RecordSource s = reader.open(f.toString(), FileFormat.CSV)) {
            assertEquals(Set.of("external_ref", "amount", "currency", "settlement_timestamp"), s.columns());
            RawRecord first = s.next();
            assertEquals(1, first.recordNumber());
            assertEquals("r-1", first.fields().get("external_ref"));
            assertEquals("10.50", first.fields().get("amount"));
            assertEquals(2, s.next().recordNumber());
            assertNull(s.next());
            assertTrue(s.bytesRead() > 0 && s.bytesRead() <= s.totalBytes());
        }
    }

    @Test
    void csvHandlesQuotedFieldsBomBlankLinesAndEmptyValues() throws IOException {
        Path f = write("b.csv", "\uFEFFexternal_ref,amount,currency,settlement_timestamp\n"
                + "\n"
                + "\"R,1\",\"1,5\",PEN,\n"
                + "R2,2,PEN,2026-03-10T15:00:00Z\n");
        List<RawRecord> rows = readAll(f, FileFormat.CSV);
        assertEquals(2, rows.size());
        assertEquals("R,1", rows.get(0).fields().get("external_ref"));
        assertEquals("1,5", rows.get(0).fields().get("amount"));
        assertFalse(rows.get(0).fields().containsKey("settlement_timestamp"), "empty values are absent");
        assertTrue(rows.get(0).rawPayload().contains("\"external_ref\":\"R,1\""));
    }

    @Test
    void csvRaggedRowsAreFlaggedNotFatal() throws IOException {
        Path f = write("c.csv", "external_ref,amount,currency,settlement_timestamp\nONLY,1\nA,1,PEN,2026-03-10T15:00:00Z,EXTRA\n");
        List<RawRecord> rows = readAll(f, FileFormat.CSV);
        assertEquals(2, rows.size());
        assertTrue(rows.get(0).fields().containsKey(SettlementRecordMapper.ROW_ERROR_FIELD));
        assertTrue(rows.get(1).fields().containsKey(SettlementRecordMapper.ROW_ERROR_FIELD));
    }

    @Test
    void csvWithDuplicatedColumnsIsRejected() throws IOException {
        Path f = write("d.csv", "external_ref,amount,AMOUNT,currency\nA,1,2,PEN\n");
        FileProcessingException ex = assertThrows(FileProcessingException.class, () -> reader.open(f.toString(), FileFormat.CSV));
        assertEquals("MALFORMED_CSV", ex.code());
    }

    @Test
    void csvWithBrokenQuotingIsAStructuralError() throws IOException {
        Path f = write("e.csv", "external_ref,amount,currency,settlement_timestamp\n\"abc\"x,1,PEN,2026-03-10T15:00:00Z\n");
        FileProcessingException ex = assertThrows(FileProcessingException.class, () -> readAll(f, FileFormat.CSV));
        assertEquals("MALFORMED_CSV", ex.code());
    }

    @Test
    void emptyCsvYieldsNoRecordsAndNoColumns() throws IOException {
        Path f = write("empty.csv", "");
        try (RecordSource s = reader.open(f.toString(), FileFormat.CSV)) {
            assertTrue(s.columns().isEmpty());
            assertNull(s.next());
        }
    }

    // ------------------------------------------------------------------ JSON

    @Test
    void jsonKeepsNumbersExactlyAsWritten() throws IOException {
        Path f = write("a.json", "[{\"External_Ref\":\"r-1\",\"amount\":100.10,\"currency\":\"PEN\","
                + "\"settlement_timestamp\":\"2026-03-10T15:00:00Z\",\"meta\":{\"k\":[1,2]},\"note\":null},"
                + "{\"external_ref\":\"r-2\",\"amount\":1E+3,\"currency\":\"USD\",\"settlement_timestamp\":\"x\"}]");
        List<RawRecord> rows = readAll(f, FileFormat.JSON);
        assertEquals(2, rows.size());
        assertEquals("100.10", rows.get(0).fields().get("amount"));
        assertEquals("r-1", rows.get(0).fields().get("external_ref"));
        assertTrue(rows.get(0).rawPayload().contains("\"amount\":100.10"));
        assertTrue(rows.get(0).rawPayload().contains("\"meta\":{\"k\":[1,2]}"));
        assertEquals("1E+3", rows.get(1).fields().get("amount"));
        assertFalse(rows.get(0).fields().containsKey("note"));
        assertEquals(Set.of(), reader.open(f.toString(), FileFormat.JSON).columns());
    }

    @Test
    void jsonEmptyArrayHasNoRecords() throws IOException {
        assertEquals(0, readAll(write("e.json", "  [ ]  "), FileFormat.JSON).size());
        assertEquals(0, readAll(write("blank.json", ""), FileFormat.JSON).size());
    }

    @Test
    void jsonStructuralErrorsAreReportedWithCodes() throws IOException {
        Path root = write("root.json", "{\"a\":1}");
        assertEquals("INVALID_JSON_STRUCTURE", assertThrows(FileProcessingException.class,
                () -> reader.open(root.toString(), FileFormat.JSON)).code());

        Path element = write("el.json", "[1,2]");
        assertEquals("INVALID_JSON_STRUCTURE", assertThrows(FileProcessingException.class,
                () -> readAll(element, FileFormat.JSON)).code());

        Path truncated = write("trunc.json", "[{\"external_ref\":\"a\"},");
        assertEquals("INVALID_JSON", assertThrows(FileProcessingException.class,
                () -> readAll(truncated, FileFormat.JSON)).code());

        Path garbage = write("garbage.json", "[{\"external_ref\": nope}]");
        FileProcessingException ex = assertThrows(FileProcessingException.class, () -> readAll(garbage, FileFormat.JSON));
        assertEquals("INVALID_JSON", ex.code());
        assertTrue(ex.getMessage().contains("line 1"));
    }

    @Test
    void missingStoredFileIsReportedClearly() {
        FileProcessingException ex = assertThrows(FileProcessingException.class,
                () -> reader.open(dir.resolve("nope.csv").toString(), FileFormat.CSV));
        assertEquals("FILE_NOT_FOUND", ex.code());
    }

    @Test
    void largeFilesAreStreamedRecordByRecord() throws IOException {
        StringBuilder sb = new StringBuilder("external_ref,amount,currency,settlement_timestamp\n");
        for (int i = 0; i < 50_000; i++) {
            sb.append("R").append(i).append(",1.00,PEN,2026-03-10T15:00:00Z\n");
        }
        Path f = write("big.csv", sb.toString());
        try (RecordSource s = reader.open(f.toString(), FileFormat.CSV)) {
            long n = 0;
            while (s.next() != null) {
                n++;
            }
            assertEquals(50_000, n);
            assertEquals(s.totalBytes(), s.bytesRead());
        }
    }

    // ------------------------------------------------------------------ storage

    @Test
    void storageComputesSha256WhileStreamingAndUsesServerGeneratedNames() throws IOException {
        LocalFileStorageAdapter storage = new LocalFileStorageAdapter(dir.resolve("store"));
        var stored = storage.store(new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)), "../../etc/passwd.csv");
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", stored.sha256());
        assertEquals(3, stored.sizeBytes());
        Path p = Path.of(stored.location());
        assertTrue(p.startsWith(dir.resolve("store").toAbsolutePath().normalize()), "no path traversal");
        assertTrue(p.getFileName().toString().endsWith(".csv"));
        assertEquals("abc", Files.readString(p));
        storage.delete(stored.location());
        assertFalse(Files.exists(p));
        storage.delete(stored.location()); // deleting twice never throws
    }

    @Test
    void sameContentGivesSameHashAndDifferentLocations() {
        LocalFileStorageAdapter storage = new LocalFileStorageAdapter(dir.resolve("s2"));
        var a = storage.store(new ByteArrayInputStream(new byte[]{1, 2, 3}), "a.json");
        var b = storage.store(new ByteArrayInputStream(new byte[]{1, 2, 3}), "b.json");
        assertEquals(a.sha256(), b.sha256());
        assertFalse(a.location().equals(b.location()));
    }
}
