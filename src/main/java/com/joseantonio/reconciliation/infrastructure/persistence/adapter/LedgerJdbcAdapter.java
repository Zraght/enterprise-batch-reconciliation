package com.joseantonio.reconciliation.infrastructure.persistence.adapter;

import com.joseantonio.reconciliation.application.port.out.LedgerPort;
import com.joseantonio.reconciliation.domain.model.AccountActivity;
import com.joseantonio.reconciliation.domain.model.AccountType;
import com.joseantonio.reconciliation.domain.model.ExternalSettlement;
import com.joseantonio.reconciliation.domain.model.LateBooking;
import com.joseantonio.reconciliation.domain.model.LedgerEntry;
import com.joseantonio.reconciliation.domain.model.LedgerStatus;
import com.joseantonio.reconciliation.domain.model.Money;
import com.joseantonio.reconciliation.domain.model.OutOfScopeLedger;
import com.joseantonio.reconciliation.domain.model.ReferenceCode;
import com.joseantonio.reconciliation.infrastructure.persistence.SqlStatements;
import com.joseantonio.reconciliation.infrastructure.persistence.TimeConversions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

@Component
public class LedgerJdbcAdapter implements LedgerPort {

    private static final int STREAM_FETCH_SIZE = 2000;
    private static final RowMapper<LedgerEntry> LEDGER = LedgerJdbcAdapter::mapLedger;

    private final JdbcTemplate jdbc;

    public LedgerJdbcAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static LedgerEntry mapLedger(ResultSet rs, int rowNum) throws SQLException {
        return new LedgerEntry(
                rs.getLong("tx_id"),
                ReferenceCode.of(rs.getString("reference_code")),
                rs.getString("account_number"),
                Money.of(rs.getBigDecimal("amount"), rs.getString("currency")),
                TimeConversions.toInstant(rs.getObject("tx_timestamp", LocalDateTime.class)),
                LedgerStatus.valueOf(rs.getString("status")),
                rs.getString("source_name"),
                AccountType.valueOf(rs.getString("account_type")),
                rs.getBoolean("pre_authorized"));
    }

    @Override
    @Transactional
    public void saveAll(List<LedgerEntry> entries) {
        jdbc.batchUpdate(SqlStatements.INSERT_LEDGER, entries, 1000, (ps, e) -> {
            ps.setString(1, e.reference().value());
            ps.setString(2, e.accountNumber());
            ps.setBigDecimal(3, e.amount().amount());
            ps.setString(4, e.amount().currencyCode());
            ps.setObject(5, TimeConversions.toUtc(e.timestamp()));
            ps.setString(6, e.status().name());
            ps.setString(7, e.sourceName());
            ps.setString(8, e.accountType().name());
            ps.setBoolean(9, e.preAuthorized());
        });
    }

    @Override
    public List<LedgerEntry> findScopeForSettlements(long batchId, String sourceName,
                                                     long settlementIdExclusive, long settlementIdInclusive) {
        return jdbc.query(SqlStatements.LEDGER_IN_SCOPE_FOR_SETTLEMENTS, LEDGER,
                batchId, settlementIdExclusive, settlementIdInclusive, sourceName);
    }

    @Override
    public List<OutOfScopeLedger> findOutOfScopeForSettlements(long batchId, String sourceName,
                                                               long settlementIdExclusive, long settlementIdInclusive) {
        return jdbc.query(SqlStatements.LEDGER_OUT_OF_SCOPE_FOR_SETTLEMENTS,
                (rs, i) -> new OutOfScopeLedger(mapLedger(rs, i), rs.getObject("reconciled_batch_id", Long.class)),
                batchId, settlementIdExclusive, settlementIdInclusive, sourceName);
    }

    @Override
    public List<LateBooking> findLateBookings(long batchId, String sourceName, long txIdExclusive, int limit) {
        return jdbc.query(SqlStatements.LEDGER_LATE_BOOKINGS, (rs, i) -> {
            LedgerEntry ledger = mapLedger(rs, i);
            ExternalSettlement settlement = new ExternalSettlement(
                    rs.getLong("s_id"),
                    ReferenceCode.of(rs.getString("s_ref")),
                    Money.of(rs.getBigDecimal("s_amount"), rs.getString("s_currency")),
                    TimeConversions.toInstant(rs.getObject("s_timestamp", LocalDateTime.class)),
                    "",
                    rs.getLong("s_record"));
            return new LateBooking(ledger, settlement, rs.getLong("origin_batch"), rs.getLong("origin_result"));
        }, limit, batchId, sourceName, txIdExclusive);
    }

    @Override
    public List<LedgerEntry> findDueUnreconciledAfter(String sourceName, Instant dueCutoff, long txIdExclusive, int limit) {
        return jdbc.query(SqlStatements.LEDGER_DUE_UNRECONCILED_PAGE, LEDGER,
                limit, sourceName, TimeConversions.toUtc(dueCutoff), txIdExclusive);
    }

    @Override
    public int countDeferred(String sourceName, Instant dueCutoff) {
        Integer count = jdbc.queryForObject(SqlStatements.LEDGER_COUNT_DEFERRED, Integer.class,
                sourceName, TimeConversions.toUtc(dueCutoff));
        return count == null ? 0 : count;
    }

    /**
     * Streams the audit scope with a forward-only cursor on its own JdbcTemplate (fetch size set), grouping rows by
     * account. NOT transactional on purpose: while the cursor is open the consumer reads history and writes alerts
     * through other connections, so the pool needs at least two connections per running batch.
     */
    @Override
    public void forEachAuditActivity(long batchId, Consumer<AccountActivity> consumer) {
        JdbcTemplate streaming = new JdbcTemplate(jdbc.getDataSource());
        streaming.setFetchSize(STREAM_FETCH_SIZE);
        AccountGrouper grouper = new AccountGrouper(consumer);
        streaming.query(SqlStatements.LEDGER_AUDIT_SCOPE, ps -> ps.setLong(1, batchId), grouper);
        grouper.flush();
    }

    @Override
    public List<LedgerEntry> findAccountHistory(Collection<String> accountNumbers, Instant from, Instant to) {
        if (accountNumbers.isEmpty()) {
            return List.of();
        }
        List<String> accounts = new ArrayList<>(accountNumbers);
        return jdbc.query(SqlStatements.ledgerAccountHistory(accounts.size()), ps -> {
            int index = 1;
            for (String account : accounts) {
                ps.setString(index++, account);
            }
            ps.setObject(index++, TimeConversions.toUtc(from));
            ps.setObject(index, TimeConversions.toUtc(to));
        }, LEDGER);
    }

    /** Rows arrive ordered by account, so a change of account closes the previous group. */
    private static final class AccountGrouper implements RowCallbackHandler {

        private final Consumer<AccountActivity> consumer;
        private String currentAccount;
        private List<LedgerEntry> currentEntries = new ArrayList<>();

        AccountGrouper(Consumer<AccountActivity> consumer) {
            this.consumer = consumer;
        }

        @Override
        public void processRow(ResultSet rs) throws SQLException {
            LedgerEntry entry = mapLedger(rs, 0);
            if (currentAccount != null && !currentAccount.equals(entry.accountNumber())) {
                flush();
            }
            currentAccount = entry.accountNumber();
            currentEntries.add(entry);
        }

        void flush() {
            if (currentAccount != null) {
                consumer.accept(new AccountActivity(currentAccount, currentEntries));
                currentEntries = new ArrayList<>();
                currentAccount = null;
            }
        }
    }
}
