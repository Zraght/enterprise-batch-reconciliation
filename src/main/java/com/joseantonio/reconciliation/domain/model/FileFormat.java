package com.joseantonio.reconciliation.domain.model;

import com.joseantonio.reconciliation.domain.exception.InvalidRequestException;

import java.util.Locale;

public enum FileFormat {
    CSV, JSON;

    public static FileFormat fromFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new InvalidRequestException("INVALID_FILE_NAME", "The uploaded file has no name");
        }
        String lower = fileName.trim().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".csv")) {
            return CSV;
        }
        if (lower.endsWith(".json")) {
            return JSON;
        }
        throw new InvalidRequestException("UNSUPPORTED_FILE_FORMAT",
                "Unsupported file type for '" + fileName + "'. Only .csv and .json files are accepted");
    }
}
