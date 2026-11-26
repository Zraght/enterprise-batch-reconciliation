package com.joseantonio.reconciliation.infrastructure.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

/** Typed, validated view of the {@code reconciliation.*} configuration. The application fails fast on bad values. */
@Validated
@ConfigurationProperties(prefix = "reconciliation")
public record ReconciliationProperties(
        @Valid @NotNull Storage storage,
        @Valid @NotNull Processing processing,
        @Valid @NotNull Matching matching,
        @Valid @NotNull Audit audit,
        @Valid @NotNull Security security) {

    public record Storage(@NotBlank String directory) {
    }

    public record Processing(
            @Min(1) @Max(5000) int chunkSize,
            @Min(1) @Max(64) int maxConcurrentBatches,
            boolean virtualThreads,
            @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal maxInvalidRatio,
            @NotNull Duration staleAfter,
            @NotNull Duration recoveryInterval,
            boolean recoveryEnabled,
            @Min(1) int recoveryLimit) {
    }

    /**
     * @param settlementLag how long after booking a gateway may take to settle a movement; a movement is only reported as
     *                      missing from a file once the file's period extends past booking time + lag (ISO-8601 duration,
     *                      PT0S = same day, P1D = T+1)
     */
    public record Matching(@NotNull Duration settlementLag) {
    }

    /**
     * @param apiKey         shared secret expected in the {@code X-API-Key} header of every request except the public
     *                       documentation/health paths; blank = authentication disabled (development only)
     * @param apiKeyRequired when true the application REFUSES TO START without a key (set by the prod profile)
     */
    public record Security(String apiKey, boolean apiKeyRequired) {
    }

    /**
     * @param thresholds account type -> currency -> limit
     */
    public record Audit(
            @NotNull Duration duplicateWindow,
            @NotNull Map<String, Map<String, BigDecimal>> thresholds,
            @Valid @NotNull OperatingHours operatingHours) {
    }

    /**
     * @param opensAt  local time HH:mm
     * @param closesAt local time HH:mm
     * @param highValue currency -> "high value" threshold
     */
    public record OperatingHours(
            @NotBlank String zone,
            @NotBlank String opensAt,
            @NotBlank String closesAt,
            @NotNull Map<String, BigDecimal> highValue) {
    }
}
