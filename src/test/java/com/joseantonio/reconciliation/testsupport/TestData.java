package com.joseantonio.reconciliation.testsupport;

import com.joseantonio.reconciliation.domain.model.AccountType;
import com.joseantonio.reconciliation.domain.model.ExternalSettlement;
import com.joseantonio.reconciliation.domain.model.LedgerEntry;
import com.joseantonio.reconciliation.domain.model.LedgerStatus;
import com.joseantonio.reconciliation.domain.model.Money;
import com.joseantonio.reconciliation.domain.model.ReferenceCode;

import java.time.Instant;

/** Small builders shared by unit tests. */
public final class TestData {

    public static final Instant T0 = Instant.parse("2026-03-10T15:00:00Z"); // 10:00 in Lima (UTC-5)

    private TestData() {
    }

    public static LedgerEntry ledger(long id, String ref, String account, String amount, String currency, Instant ts) {
        return ledger(id, ref, account, amount, currency, ts, AccountType.RETAIL, false);
    }

    public static LedgerEntry ledger(long id, String ref, String account, String amount, String currency,
                                     Instant ts, AccountType type, boolean preAuthorized) {
        return new LedgerEntry(id, ReferenceCode.of(ref), account, Money.of(amount, currency), ts,
                LedgerStatus.POSTED, "NIUBIZ", type, preAuthorized);
    }

    /** Ledger movement booked at {@code ts} (id assigned by the store), POSTED, source NIUBIZ, RETAIL. */
    public static LedgerEntry entry(String ref, String account, String amount, String currency, Instant ts) {
        return entry(ref, account, amount, currency, ts, LedgerStatus.POSTED, "NIUBIZ");
    }

    public static LedgerEntry entry(String ref, String account, String amount, String currency, Instant ts,
                                    LedgerStatus status, String source) {
        return new LedgerEntry(null, ReferenceCode.of(ref), account, Money.of(amount, currency), ts, status, source,
                AccountType.RETAIL, false);
    }

    public static ExternalSettlement settlement(long id, String ref, String amount, String currency) {
        return new ExternalSettlement(id, ReferenceCode.of(ref), Money.of(amount, currency), T0,
                "{\"external_ref\":\"" + ref + "\"}", id);
    }
}
