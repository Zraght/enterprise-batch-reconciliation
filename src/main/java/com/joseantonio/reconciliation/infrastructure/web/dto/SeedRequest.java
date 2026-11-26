package com.joseantonio.reconciliation.infrastructure.web.dto;

import com.joseantonio.reconciliation.application.port.in.SeedCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Parameters of the synthetic data generator (dev/test only). Every optional field has a default.")
public record SeedRequest(
        @Schema(example = "NIUBIZ") @NotBlank @Size(max = 100) String sourceName,
        @Schema(description = "Ledger entries of the base data set", example = "1000", defaultValue = "1000")
        @Min(1) @Max(SeedCommand.MAX_COUNT) Integer count,
        @Schema(description = "Same seed => same data. Omit for a time-based seed", example = "42") Long randomSeed,
        @Schema(defaultValue = "0.03") @DecimalMin("0") @DecimalMax("0.5") Double missingExternalRate,
        @Schema(defaultValue = "0.04") @DecimalMin("0") @DecimalMax("0.5") Double amountMismatchRate,
        @Schema(defaultValue = "0.02") @DecimalMin("0") @DecimalMax("0.5") Double missingInternalRate,
        @Schema(defaultValue = "0.01") @DecimalMin("0") @DecimalMax("0.5") Double invalidRecordRate,
        @Schema(defaultValue = "0.005") @DecimalMin("0") @DecimalMax("0.5") Double duplicateReferenceRate,
        @Schema(defaultValue = "0.02") @DecimalMin("0") @DecimalMax("0.5") Double duplicateBurstRate,
        @Schema(description = "Also upload the generated settlement file as a new batch", defaultValue = "true")
        Boolean submit) {

    public SeedCommand toCommand() {
        return new SeedCommand(sourceName,
                count == null ? 1000 : count,
                randomSeed,
                missingExternalRate == null ? 0.03 : missingExternalRate,
                amountMismatchRate == null ? 0.04 : amountMismatchRate,
                missingInternalRate == null ? 0.02 : missingInternalRate,
                invalidRecordRate == null ? 0.01 : invalidRecordRate,
                duplicateReferenceRate == null ? 0.005 : duplicateReferenceRate,
                duplicateBurstRate == null ? 0.02 : duplicateBurstRate,
                submit == null || submit);
    }
}
