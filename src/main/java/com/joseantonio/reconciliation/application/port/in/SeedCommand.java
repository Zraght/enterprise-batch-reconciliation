package com.joseantonio.reconciliation.application.port.in;

import com.joseantonio.reconciliation.domain.exception.InvalidRequestException;

/**
 * Parameters of the synthetic data generator. Rates are fractions of {@code count} (0..0.5 each).
 *
 * @param count                   ledger entries that exist in the base data set
 * @param randomSeed              makes the data set reproducible; {@code null} = derived from the clock
 * @param missingExternalRate     ledger entries that are absent from the settlement file
 * @param amountMismatchRate      entries present in both but with a different amount (20% of them: different currency)
 * @param missingInternalRate     extra settlements that have no ledger entry
 * @param invalidRecordRate       malformed rows injected in the file
 * @param duplicateReferenceRate  settlement rows repeated within the same file
 * @param duplicateBurstRate      ledger entries followed by a same-account/same-amount twin within 5-25 seconds
 * @param submit                  also upload the generated file as a new batch
 */
public record SeedCommand(
        String sourceName,
        int count,
        Long randomSeed,
        double missingExternalRate,
        double amountMismatchRate,
        double missingInternalRate,
        double invalidRecordRate,
        double duplicateReferenceRate,
        double duplicateBurstRate,
        boolean submit) {

    public static final int MAX_COUNT = 200_000;

    public SeedCommand {
        sourceName = com.joseantonio.reconciliation.domain.model.SourceName.require(sourceName);
        if (count < 1 || count > MAX_COUNT) {
            throw new InvalidRequestException("INVALID_COUNT", "count must be between 1 and " + MAX_COUNT);
        }
        for (double rate : new double[]{missingExternalRate, amountMismatchRate, missingInternalRate,
                invalidRecordRate, duplicateReferenceRate, duplicateBurstRate}) {
            if (Double.isNaN(rate) || rate < 0 || rate > 0.5) {
                throw new InvalidRequestException("INVALID_RATE", "every rate must be between 0 and 0.5");
            }
        }
        if (missingExternalRate + amountMismatchRate > 0.9) {
            throw new InvalidRequestException("INVALID_RATE", "missingExternalRate + amountMismatchRate must be <= 0.9");
        }
    }

    public static SeedCommand defaults(String sourceName, int count, Long randomSeed, boolean submit) {
        return new SeedCommand(sourceName, count, randomSeed, 0.03, 0.04, 0.02, 0.01, 0.005, 0.02, submit);
    }
}
