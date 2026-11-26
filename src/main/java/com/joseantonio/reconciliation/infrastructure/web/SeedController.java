package com.joseantonio.reconciliation.infrastructure.web;

import com.joseantonio.reconciliation.application.port.in.SeedLedgerUseCase;
import com.joseantonio.reconciliation.infrastructure.web.dto.SeedRequest;
import com.joseantonio.reconciliation.infrastructure.web.dto.SeedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Only registered under the {@code dev} and {@code test} profiles: with any other profile (e.g. {@code prod}) the bean
 * does not exist and the endpoint answers 404, so it can never expose test-data generation in production.
 */
@RestController
@RequestMapping("/api/v1/ledger")
@Profile({"dev", "test"})
@Tag(name = "Seed (dev/test only)", description = "Synthetic data for demos and automated tests")
public class SeedController {

    private final SeedLedgerUseCase seedUseCase;

    public SeedController(SeedLedgerUseCase seedUseCase) {
        this.seedUseCase = seedUseCase;
    }

    @Operation(summary = "Generate a synthetic internal ledger and its matching settlement file",
            description = """
                    Creates ledger entries for the given source plus a settlement file that contains a known mix of exact
                    matches, amount/currency mismatches, orphans on both sides, invalid rows, repeated references and
                    duplicate bursts. The response states what a correct reconciliation must report. With submit=true
                    (default) the file is uploaded as a batch. Same randomSeed => same data; re-using a seed for the same
                    source is rejected (409) because ledger references are unique.""")
    @ApiResponse(responseCode = "201", description = "Data generated")
    @ApiResponse(responseCode = "400", description = "Invalid parameters", content = @Content)
    @ApiResponse(responseCode = "409", description = "That seed was already applied for the source", content = @Content)
    @PostMapping("/seed")
    @ResponseStatus(HttpStatus.CREATED)
    public SeedResponse seed(@Valid @RequestBody SeedRequest request) {
        return SeedResponse.from(seedUseCase.seed(request.toCommand()));
    }
}
