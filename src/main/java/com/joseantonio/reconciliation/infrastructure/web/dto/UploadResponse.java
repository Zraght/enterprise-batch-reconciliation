package com.joseantonio.reconciliation.infrastructure.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Result of registering an uploaded file as a reconciliation batch")
public record UploadResponse(
        @Schema(description = "Identifier of the batch", example = "42") long batchId,
        @Schema(description = "Current batch status", example = "PENDING") String status,
        @Schema(description = "CREATED = new batch queued; RETRIED = a previously FAILED batch for the same file was "
                + "re-queued; EXISTING = idempotent replay of an already registered file", example = "CREATED")
        String outcome,
        @Schema(description = "Where to follow the progress", example = "/api/v1/batches/42/status") String statusUrl) {
}
