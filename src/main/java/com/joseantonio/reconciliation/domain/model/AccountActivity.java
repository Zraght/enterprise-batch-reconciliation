package com.joseantonio.reconciliation.domain.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Ledger movements of one account, ordered chronologically.
 *
 * @param entries the movements being audited now (the scope of the current batch)
 * @param history other POSTED movements of the same account, from ANY batch, source or reconciliation state, used only
 *                as context so that a rule can compare an audited movement with its neighbours in time (e.g. a
 *                temporal duplicate whose twin was reconciled in a different batch). History never gets alerts of its
 *                own here, and movements already present in {@code entries} are removed from it.
 */
public record AccountActivity(String accountNumber, List<LedgerEntry> entries, List<LedgerEntry> history) {

    private static final Comparator<LedgerEntry> CHRONOLOGICAL =
            Comparator.comparing(LedgerEntry::timestamp)
                    .thenComparingLong(e -> e.txId() == null ? 0L : e.txId());

    public AccountActivity {
        Objects.requireNonNull(accountNumber, "accountNumber");
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(history, "history");
        for (LedgerEntry e : entries) {
            requireSameAccount(accountNumber, e);
        }
        for (LedgerEntry e : history) {
            requireSameAccount(accountNumber, e);
        }
        entries = entries.stream().sorted(CHRONOLOGICAL).toList();
        Set<String> audited = new HashSet<>();
        entries.forEach(e -> audited.add(e.identity()));
        history = history.stream().filter(h -> !audited.contains(h.identity())).sorted(CHRONOLOGICAL).toList();
    }

    public AccountActivity(String accountNumber, List<LedgerEntry> entries) {
        this(accountNumber, entries, List.of());
    }

    /** Audited movements and history merged and ordered chronologically. */
    public List<LedgerEntry> timeline() {
        List<LedgerEntry> all = new ArrayList<>(entries.size() + history.size());
        all.addAll(entries);
        all.addAll(history);
        all.sort(CHRONOLOGICAL);
        return List.copyOf(all);
    }

    public Set<String> auditedIdentities() {
        Set<String> ids = new HashSet<>();
        entries.forEach(e -> ids.add(e.identity()));
        return ids;
    }

    private static void requireSameAccount(String accountNumber, LedgerEntry e) {
        if (!e.accountNumber().equals(accountNumber)) {
            throw new IllegalArgumentException("Entry " + e.reference() + " belongs to account "
                    + e.accountNumber() + ", not " + accountNumber);
        }
    }
}
