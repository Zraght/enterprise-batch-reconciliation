package com.joseantonio.reconciliation.infrastructure.batch;

import com.joseantonio.reconciliation.application.port.out.RecordSource;
import com.joseantonio.reconciliation.application.port.out.SettlementFileReaderPort;
import com.joseantonio.reconciliation.domain.exception.FileProcessingException;
import com.joseantonio.reconciliation.domain.model.FileFormat;

import java.nio.file.Files;
import java.nio.file.Path;

public final class SettlementFileReaderAdapter implements SettlementFileReaderPort {

    @Override
    public RecordSource open(String location, FileFormat format) {
        Path file = Path.of(location);
        if (!Files.isRegularFile(file)) {
            throw new FileProcessingException("FILE_NOT_FOUND", "The stored file is no longer available");
        }
        return switch (format) {
            case CSV -> new CsvRecordSource(file);
            case JSON -> new JsonRecordSource(file);
        };
    }
}
