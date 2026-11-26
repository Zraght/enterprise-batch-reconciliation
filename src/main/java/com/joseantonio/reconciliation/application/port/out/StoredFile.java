package com.joseantonio.reconciliation.application.port.out;

public record StoredFile(String location, String sha256, long sizeBytes) {
}
