package com.joseantonio.reconciliation.infrastructure.batch;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.joseantonio.reconciliation.application.port.out.RawRecord;
import com.joseantonio.reconciliation.application.port.out.RecordSource;
import com.joseantonio.reconciliation.domain.exception.FileProcessingException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Streaming reader for a JSON file whose root is an array of flat objects. Only the streaming API is used, so
 * memory does not depend on file size. Numbers are taken from their ORIGINAL text, never through double,
 * so "100.10" stays "100.10". The raw payload keeps the record verbatim (numbers included).
 */
final class JsonRecordSource implements RecordSource {

    private static final JsonFactory JSON = new JsonFactory();

    private final CountingInputStream counting;
    private final JsonParser parser;
    private final long totalBytes;
    private long recordNumber;
    private boolean finished;

    JsonRecordSource(Path file) {
        CountingInputStream in = null;
        JsonParser p = null;
        try {
            this.totalBytes = Files.size(file);
            in = new CountingInputStream(new BufferedInputStream(Files.newInputStream(file)));
            p = JSON.createParser(in);
            JsonToken first = p.nextToken();
            if (first == null) {
                this.finished = true;
            } else if (first != JsonToken.START_ARRAY) {
                throw new FileProcessingException("INVALID_JSON_STRUCTURE",
                        "The JSON root must be an array of settlement objects");
            }
            this.counting = in;
            this.parser = p;
        } catch (FileProcessingException e) {
            closeQuietly(p, in);
            throw e;
        } catch (JsonParseException e) {
            closeQuietly(p, in);
            throw invalidJson(e);
        } catch (IOException | RuntimeException e) {
            closeQuietly(p, in);
            throw new FileProcessingException("UNREADABLE_FILE", "The stored JSON file could not be read", e);
        }
    }

    @Override
    public RawRecord next() {
        if (finished) {
            return null;
        }
        try {
            JsonToken token = parser.nextToken();
            if (token == JsonToken.END_ARRAY) {
                finished = true;
                return null;
            }
            if (token == null) {
                throw new FileProcessingException("INVALID_JSON", "Unexpected end of file: the array is not closed");
            }
            recordNumber++;
            if (token != JsonToken.START_OBJECT) {
                throw new FileProcessingException("INVALID_JSON_STRUCTURE",
                        "Array element #" + recordNumber + " is not a JSON object");
            }
            return readObject();
        } catch (JsonParseException e) {
            throw invalidJson(e);
        } catch (IOException e) {
            throw new FileProcessingException("UNREADABLE_FILE", "The stored JSON file could not be read", e);
        }
    }

    private RawRecord readObject() throws IOException {
        Map<String, String> fields = new LinkedHashMap<>();
        StringWriter payload = new StringWriter();
        try (JsonGenerator out = JSON.createGenerator(payload)) {
            out.writeStartObject();
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String name = parser.currentName();
                JsonToken value = parser.nextToken();
                out.writeFieldName(name);
                switch (value) {
                    case VALUE_STRING -> {
                        String text = parser.getText();
                        out.writeString(text);
                        put(fields, name, text);
                    }
                    case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> {
                        String text = parser.getText();
                        out.writeRawValue(text);
                        put(fields, name, text);
                    }
                    case VALUE_TRUE -> {
                        out.writeBoolean(true);
                        put(fields, name, "true");
                    }
                    case VALUE_FALSE -> {
                        out.writeBoolean(false);
                        put(fields, name, "false");
                    }
                    case VALUE_NULL -> out.writeNull();
                    default -> out.copyCurrentStructure(parser); // nested object/array: kept in payload, not mapped
                }
            }
            out.writeEndObject();
        }
        return new RawRecord(recordNumber, fields, payload.toString());
    }

    private static void put(Map<String, String> fields, String name, String text) {
        String trimmed = text.trim();
        if (!trimmed.isEmpty()) {
            fields.put(name.trim().toLowerCase(Locale.ROOT), trimmed);
        }
    }

    private FileProcessingException invalidJson(Exception e) {
        String where = "";
        if (e instanceof JsonParseException jpe && jpe.getLocation() != null) {
            where = " at line " + jpe.getLocation().getLineNr() + ", column " + jpe.getLocation().getColumnNr();
        }
        String reason = e instanceof JsonParseException jpe ? jpe.getOriginalMessage() : "unreadable";
        return new FileProcessingException("INVALID_JSON", "Malformed JSON" + where + ": " + reason, e);
    }

    @Override
    public Set<String> columns() {
        return Set.of();
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
