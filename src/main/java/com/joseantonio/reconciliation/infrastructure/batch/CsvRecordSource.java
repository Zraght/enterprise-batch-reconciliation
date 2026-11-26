package com.joseantonio.reconciliation.infrastructure.batch;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.joseantonio.reconciliation.application.port.out.RawRecord;
import com.joseantonio.reconciliation.application.port.out.RecordSource;
import com.joseantonio.reconciliation.application.service.SettlementRecordMapper;
import com.joseantonio.reconciliation.domain.exception.FileProcessingException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Streaming RFC-4180 CSV reader (UTF-8, header row required). Header names are normalised to lower case;
 * a UTF-8 BOM is tolerated. Rows with the wrong number of columns are surfaced with a row-error marker
 * so that they become invalid records instead of aborting the file.
 */
final class CsvRecordSource implements RecordSource {

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(true)
            .setTrim(true)
            .build();
    private static final JsonFactory JSON = new JsonFactory();

    private final CountingInputStream counting;
    private final CSVParser parser;
    private final Iterator<CSVRecord> iterator;
    private final List<String> header;
    private final long totalBytes;

    CsvRecordSource(Path file) {
        CountingInputStream in = null;
        CSVParser p = null;
        try {
            this.totalBytes = Files.size(file);
            in = new CountingInputStream(new BufferedInputStream(Files.newInputStream(file)));
            p = FORMAT.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
            List<String> names = new ArrayList<>();
            for (String name : p.getHeaderNames()) {
                String normalised = normalise(name);
                if (normalised.isEmpty() || names.contains(normalised)) {
                    throw new FileProcessingException("MALFORMED_CSV",
                            "The CSV header has an empty or duplicated column name: '" + name + "'");
                }
                names.add(normalised);
            }
            this.header = List.copyOf(names);
            this.iterator = p.iterator();
            this.counting = in;
            this.parser = p;
        } catch (FileProcessingException e) {
            closeQuietly(p, in);
            throw e;
        } catch (IOException e) {
            closeQuietly(p, in);
            // The message of an IOException usually contains the server-side path: keep it in the cause (logs only).
            throw new FileProcessingException("UNREADABLE_FILE", "The stored CSV file could not be read", e);
        } catch (RuntimeException e) {
            closeQuietly(p, in);
            throw new FileProcessingException("MALFORMED_CSV", "The CSV header cannot be parsed", e);
        }
    }

    @Override
    public RawRecord next() {
        try {
            if (!iterator.hasNext()) {
                return null;
            }
            CSVRecord record = iterator.next();
            Map<String, String> fields = new LinkedHashMap<>();
            StringWriter payload = new StringWriter();
            try (JsonGenerator json = JSON.createGenerator(payload)) {
                json.writeStartObject();
                for (int i = 0; i < record.size(); i++) {
                    String value = record.get(i);
                    String name = i < header.size() ? header.get(i) : "_extra_" + (i - header.size() + 1);
                    json.writeStringField(name, value);
                    if (i < header.size() && !value.isEmpty()) {
                        fields.put(name, value);
                    }
                }
                json.writeEndObject();
            }
            if (record.size() != header.size()) {
                fields.put(SettlementRecordMapper.ROW_ERROR_FIELD,
                        "Row has " + record.size() + " columns but the header declares " + header.size());
            }
            return new RawRecord(record.getRecordNumber(), fields, payload.toString());
        } catch (IOException | RuntimeException e) {
            throw new FileProcessingException("MALFORMED_CSV", "The CSV file is malformed near record "
                    + (parser.getRecordNumber() + 1), e);
        }
    }

    @Override
    public Set<String> columns() {
        return new LinkedHashSet<>(header);
    }

    @Override
    public long bytesRead() {
        return counting.count();
    }

    @Override
    public long totalBytes() {
        return totalBytes;
    }

    @Override
    public void close() {
        closeQuietly(parser, counting);
    }

    private static String normalise(String name) {
        if (name == null) {
            return "";
        }
        return name.replace("\uFEFF", "").trim().toLowerCase(Locale.ROOT);
    }

    private static void closeQuietly(AutoCloseable... closeables) {
        for (AutoCloseable c : closeables) {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignored) {
                    // nothing useful can be done while closing a read-only file
                }
            }
        }
    }
}
