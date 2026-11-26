package com.joseantonio.reconciliation.application.service;

import com.joseantonio.reconciliation.application.port.in.SeedCommand;
import com.joseantonio.reconciliation.application.port.in.SeedResult;
import com.joseantonio.reconciliation.domain.model.AccountType;
import com.joseantonio.reconciliation.domain.model.LedgerEntry;
import com.joseantonio.reconciliation.domain.model.LedgerStatus;
import com.joseantonio.reconciliation.domain.model.Money;
import com.joseantonio.reconciliation.domain.model.ReferenceCode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Deterministic synthetic data (same seed -> same data). Produces an internal ledger plus the matching external
 * settlement file in CSV, with a known mix of matches, mismatches, orphans, invalid rows, repeated references and
 * duplicate bursts, and reports what a correct reconciliation must find under the given settlement lag.
 *
 * <p>The expectation follows the real scope rules: a ledger movement absent from the file is only an expected
 * MISSING_EXTERNAL when it is DUE (booked up to {@code period end - lag}, where the period end is the latest settlement
 * timestamp of the file); the others are expected to be deferred, i.e. NOT reported.</p>
 *
 * <p>Amounts are generated as integer cents -> BigDecimal; no floating point touches money. {@link Random} is fine
 * here: this is test data, not a security-sensitive value.</p>
 */
public final class SyntheticDataGenerator {

    public record DataSet(List<LedgerEntry> ledger, String settlementCsv, int settlementRecords,
                          SeedResult.Expected expected, long randomSeed) {
    }

    private static final String HEADER = "external_ref,amount,currency,settlement_timestamp";

    public DataSet generate(SeedCommand cmd, Instant clockNow, Duration settlementLag) {
        Instant now = clockNow.truncatedTo(ChronoUnit.SECONDS);
        long seed = cmd.randomSeed() != null ? cmd.randomSeed() : now.toEpochMilli();
        Random rnd = new Random(seed);
        String tag = Long.toString(Math.abs(seed % 100_000_000L), 36).toUpperCase(Locale.ROOT);
        int count = cmd.count();
        int accountPool = Math.max(10, count / 5);

        List<LedgerEntry> ledger = new ArrayList<>(count + count / 10);
        List<String> rows = new ArrayList<>(count + count / 5);
        List<String> validRows = new ArrayList<>(count);
        List<Instant> missingExternalBookedAt = new ArrayList<>();
        Instant latestValidRow = null;
        long matched = 0;
        long mismatch = 0;
        long missingInternal = 0;
        long invalid = 0;

        for (int i = 1; i <= count; i++) {
            String ref = "SD" + tag + "-" + String.format("%06d", i);
            AccountType type = pickType(rnd);
            String currency = rnd.nextInt(100) < 85 ? "PEN" : "USD";
            long cents = 100 + rnd.nextInt(2_000_000);
            if (type == AccountType.CORPORATE) {
                cents *= 25;
            } else if (type == AccountType.BUSINESS) {
                cents *= 3;
            }
            Instant ts = now.minusSeconds(60L + rnd.nextInt(3 * 86_400));
            LedgerEntry entry = new LedgerEntry(null, ReferenceCode.of(ref),
                    "ACC-" + String.format("%05d", 10_000 + rnd.nextInt(accountPool)),
                    Money.of(BigDecimal.valueOf(cents, 2), currency), ts, LedgerStatus.POSTED,
                    cmd.sourceName(), type, rnd.nextInt(100) < 10);
            ledger.add(entry);

            double roll = rnd.nextDouble();
            Instant settledAt = earlier(ts.plusSeconds(rnd.nextInt(3600)), now);
            if (roll < cmd.missingExternalRate()) {
                missingExternalBookedAt.add(ts);
            } else if (roll < cmd.missingExternalRate() + cmd.amountMismatchRate()) {
                mismatch++;
                String row;
                if (rnd.nextInt(5) == 0) {
                    String other = currency.equals("PEN") ? "USD" : "PEN";
                    row = row(ref, BigDecimal.valueOf(cents, 2).toPlainString(), other, settledAt);
                } else {
                    long delta = 1 + rnd.nextInt(5000);
                    long changed = rnd.nextBoolean() ? cents + delta : Math.max(1, cents - delta);
                    if (changed == cents) {
                        changed = cents + 1;
                    }
                    row = row(ref, BigDecimal.valueOf(changed, 2).toPlainString(), currency, settledAt);
                }
                rows.add(row);
                validRows.add(row);
                latestValidRow = later(latestValidRow, settledAt);
            } else {
                matched++;
                String row = row(ref, BigDecimal.valueOf(cents, 2).toPlainString(), currency, settledAt);
                rows.add(row);
                validRows.add(row);
                latestValidRow = later(latestValidRow, settledAt);
            }
        }

        // Duplicate bursts: same account, same amount, 5-25 s later. Both sides carry the twin, so it reconciles as MATCHED.
        int bursts = (int) Math.round(count * cmd.duplicateBurstRate());
        for (int k = 1; k <= bursts; k++) {
            LedgerEntry base = ledger.get(rnd.nextInt(count));
            Instant twinTs = base.timestamp().plusSeconds(5 + rnd.nextInt(21));
            String ref = "SD" + tag + "-D" + String.format("%05d", k);
            ledger.add(new LedgerEntry(null, ReferenceCode.of(ref), base.accountNumber(), base.amount(), twinTs,
                    LedgerStatus.POSTED, cmd.sourceName(), base.accountType(), base.preAuthorized()));
            Instant settledAt = earlier(twinTs.plusSeconds(60), now);
            String row = row(ref, base.amount().amount().toPlainString(), base.amount().currencyCode(), settledAt);
            rows.add(row);
            validRows.add(row);
            latestValidRow = later(latestValidRow, settledAt);
            matched++;
        }

        // Settlements that the bank never booked.
        int orphans = (int) Math.round(count * cmd.missingInternalRate());
        for (int k = 1; k <= orphans; k++) {
            Instant settledAt = now.minusSeconds(60L + rnd.nextInt(86_400));
            String row = row("SX" + tag + "-" + String.format("%06d", k),
                    BigDecimal.valueOf(100 + rnd.nextInt(500_000), 2).toPlainString(), "PEN", settledAt);
            rows.add(row);
            validRows.add(row);
            latestValidRow = later(latestValidRow, settledAt);
            missingInternal++;
        }

        // Malformed rows.
        int invalidRows = (int) Math.round(count * cmd.invalidRecordRate());
        Instant anyTs = now.minusSeconds(3600);
        for (int k = 0; k < invalidRows; k++) {
            String ref = "SI" + tag + "-" + String.format("%05d", k);
            rows.add(switch (k % 6) {
                case 0 -> row(ref, "abc", "PEN", anyTs);
                case 1 -> row(ref, "10.00", "XYZ", anyTs);
                case 2 -> row("", "10.00", "PEN", anyTs);
                case 3 -> row(ref, "-10.00", "PEN", anyTs);
                case 4 -> ref + ",10.00,PEN,not-a-date";
                default -> row(ref, "10.12345", "PEN", anyTs);
            });
            invalid++;
        }

        // Repeated references inside the same file (the extra occurrence is rejected, the first one counts).
        int duplicates = validRows.isEmpty() ? 0 : (int) Math.round(count * cmd.duplicateReferenceRate());
        for (int k = 0; k < duplicates; k++) {
            rows.add(validRows.get(rnd.nextInt(validRows.size())));
            invalid++;
        }

        // Scope: only DUE movements absent from the file are discrepancies; the rest are pending settlement.
        long missingExternal = 0;
        long deferred = 0;
        if (latestValidRow != null) {
            Instant dueCutoff = latestValidRow.minus(settlementLag);
            for (Instant booked : missingExternalBookedAt) {
                if (booked.isAfter(dueCutoff)) {
                    deferred++;
                } else {
                    missingExternal++;
                }
            }
        } else {
            deferred = missingExternalBookedAt.size();
        }

        Collections.shuffle(rows, rnd);
        StringBuilder csv = new StringBuilder(rows.size() * 70).append(HEADER).append('\n');
        for (String r : rows) {
            csv.append(r).append('\n');
        }
        var expected = new SeedResult.Expected(matched, mismatch, missingExternal, missingInternal, invalid, deferred, bursts);
        return new DataSet(ledger, csv.toString(), rows.size(), expected, seed);
    }

    private static Instant earlier(Instant a, Instant b) {
        return a.isAfter(b) ? b : a;
    }

    private static Instant later(Instant current, Instant candidate) {
        return current == null || candidate.isAfter(current) ? candidate : current;
    }

    private static AccountType pickType(Random rnd) {
        int p = rnd.nextInt(100);
        return p < 70 ? AccountType.RETAIL : p < 95 ? AccountType.BUSINESS : AccountType.CORPORATE;
    }

    private static String row(String ref, String amount, String currency, Instant ts) {
        return ref + "," + amount + "," + currency + "," + ts;
    }
}
