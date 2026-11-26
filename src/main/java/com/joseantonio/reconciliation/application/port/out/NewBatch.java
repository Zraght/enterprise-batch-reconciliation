package com.joseantonio.reconciliation.application.port.out;

import com.joseantonio.reconciliation.domain.model.FileFormat;

import java.time.Instant;

/** @param declaredPeriodStart settlement period declared at upload, both ends or null */
public record NewBatch(
        String sourceName,
        String fileName,
        FileFormat fileFormat,
        String fileSha256,
        long fileSizeBytes,
        String storedFileLocation,
        Instant executionDate,
        Instant declaredPeriodStart,
        Instant declaredPeriodEnd) {
}
