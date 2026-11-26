package com.joseantonio.reconciliation.infrastructure.web.dto;

import com.joseantonio.reconciliation.application.port.in.SeedResult;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "What was generated and what a correct reconciliation of it must report")
public record SeedResponse(
        String sourceName,
        long randomSeed,
        int ledgerEntriesCreated,
        int settlementRecordsGenerated,
        SeedResult.Expected expected,
        @Schema(description = "File name (inside the storage directory) of the generated file; only when submit=false")
        String settlementFileName,
        @Schema(description = "Present when the file was submitted: follow it with /batches/{batchId}/status")
        Long batchId) {

    public static SeedResponse from(SeedResult r) {
        return new SeedResponse(r.sourceName(), r.randomSeed(), r.ledgerEntriesCreated(), r.settlementRecordsGenerated(),
                r.expected(), r.settlementFileName(), r.batchId());
    }
}
