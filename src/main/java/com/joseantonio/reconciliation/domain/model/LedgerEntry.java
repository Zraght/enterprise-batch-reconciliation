package com.joseantonio.reconciliation.domain.model;

import com.joseantonio.reconciliation.domain.exception.InvalidTransactionException;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Accounting movement recorded in the internal core-banking ledger.
 *
 * @param txId          database identity; {@code null} until persisted
 * @param sourceName    payment gateway / clearing house this movement is expected to settle with
 * @param preAuthorized whether a prior corporate authorisation exists (used by the threshold audit rule)
 */
public record LedgerEntry(
        Long txId,
        ReferenceCode reference,
        String accountNumber,
        Money amount,
        Instant timestamp,
        LedgerStatus status,
        String sourceName,
        AccountType accountType,
        boolean preAuthorized) {

    private static final Pattern ACCOUNT = Pattern.compile("[A-Z0-9\\-]{4,34}");

    public LedgerEntry {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(accountType, "accountType");
        if (sourceName == null || sourceName.isBlank()) {
            throw new InvalidTransactionException("MISSING_SOURCE", "Ledger entry source is required");
        }
        sourceName = sourceName.trim();
        if (accountNumber == null || accountNumber.isBlank()) {
            throw new InvalidTransactionException("MISSING_ACCOUNT", "Account number is required");
        }
        accountNumber = accountNumber.trim().toUpperCase(Locale.ROOT);
        if (!ACCOUNT.matcher(accountNumber).matches()) {
            throw new InvalidTransactionException("INVALID_ACCOUNT", "Account number must be 4-34 characters of A-Z, 0-9, '-'");
        }
    }

    /**
     * Stable identity of the movement: the database id once persisted, otherwise (source, reference), which is unique
     * for POSTED movements.
     */
    public String identity() {
        return txId != null ? "T" + txId : "R" + sourceName.toLowerCase(java.util.Locale.ROOT) + "|" + reference.value();
    }
}
