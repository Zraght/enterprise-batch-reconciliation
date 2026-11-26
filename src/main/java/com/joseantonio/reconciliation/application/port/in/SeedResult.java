package com.joseantonio.reconciliation.application.port.in;

/**
 * @param expected          what a correct reconciliation of the generated data must report (useful to verify the engine)
 * @param settlementFileName file name of the generated settlement file when it was only stored (submit=false)
 * @param batchId           present when the file was submitted
 */
public record SeedResult(
        String sourceName,
        long randomSeed,
        int ledgerEntriesCreated,
        int settlementRecordsGenerated,
        Expected expected,
        String settlementFileName,
        Long batchId) {

    /**
     * @param missingExternal         DUE ledger movements absent from the file
     * @param deferredLedgerEntries   ledger movements absent from the file but still within the settlement lag
     *                                (not discrepancies)
     */
    public record Expected(
            long matched,
            long amountMismatch,
            long missingExternal,
            long missingInternal,
            long invalidRecords,
            long deferredLedgerEntries,
            long minTemporalDuplicateAlerts) {
    }
}
