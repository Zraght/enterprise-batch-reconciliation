package com.joseantonio.reconciliation.infrastructure.persistence.adapter;

import com.joseantonio.reconciliation.application.port.out.ClaimedBatch;
import com.joseantonio.reconciliation.application.port.out.IngestionChunk;
import com.joseantonio.reconciliation.application.port.out.SettlementStorePort;
import com.joseantonio.reconciliation.domain.exception.BatchOwnershipLostException;
import com.joseantonio.reconciliation.domain.model.ExternalSettlement;
import com.joseantonio.reconciliation.domain.model.Money;
import com.joseantonio.reconciliation.domain.model.ReferenceCode;
import com.joseantonio.reconciliation.infrastructure.persistence.SqlStatements;
import com.joseantonio.reconciliation.infrastructure.persistence.TimeConversions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Bulk persistence of settlements with plain JDBC batching: Hibernate cannot batch IDENTITY inserts on SQL Server,
 * and a chunk of 1000 rows through the persistence context would be needlessly slow and memory hungry.
 */
@Component
public class SettlementJdbcAdapter implements SettlementStorePort {

    private final JdbcTemplate jdbc;

    public SettlementJdbcAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One transaction per chunk: counters, valid rows and invalid rows commit together or not at all. The counters
     * UPDATE is also the ownership check, so a stale worker fails here, before inserting anything.
     */
    @Override
    @Transactional
    public void saveChunk(ClaimedBatch batch, IngestionChunk chunk) {
        int updated = jdbc.update(SqlStatements.UPDATE_INGESTION_COUNTERS, chunk.recordsRead(), chunk.valid().size(), chunk.invalid().size(),
                chunk.progressPercent(), batch.batchId(), batch.attempt());
        if (updated != 1) {
            throw new BatchOwnershipLostException(batch.batchId(), batch.attempt());
        }
        if (!chunk.valid().isEmpty()) {
            jdbc.batchUpdate(SqlStatements.INSERT_SETTLEMENT, chunk.valid(), chunk.valid().size(), (ps, s) -> {
                ps.setLong(1, batch.batchId());
                ps.setString(2, s.externalRef().value());
                ps.setBigDecimal(3, s.amount().amount());
                ps.setString(4, s.amount().currencyCode());
                ps.setObject(5, TimeConversions.toUtc(s.timestamp()));
                ps.setString(6, s.rawPayload());
                ps.setLong(7, s.recordNumber());
            });
        }
        if (!chunk.invalid().isEmpty()) {
            jdbc.batchUpdate(SqlStatements.INSERT_INVALID_RECORD, chunk.invalid(), chunk.invalid().size(), (ps, r) -> {
                ps.setLong(1, batch.batchId());
                ps.setLong(2, r.recordNumber());
                ps.setString(3, r.reasonCode());
                ps.setString(4, r.message());
                ps.setString(5, r.rawPayload());
            });
        }
    }

    @Override
    public List<ExternalSettlement> findAfter(long batchId, long settlementIdExclusive, int limit) {
        return jdbc.query(SqlStatements.SELECT_SETTLEMENTS_PAGE, (rs, i) -> new ExternalSettlement(
                        rs.getLong("settlement_id"),
                        ReferenceCode.of(rs.getString("external_ref")),
                        Money.of(rs.getBigDecimal("amount"), rs.getString("currency")),
                        TimeConversions.toInstant(rs.getObject("settlement_timestamp", LocalDateTime.class)),
                        "",
                        rs.getLong("record_number")),
                limit, batchId, settlementIdExclusive);
    }

    @Override
    public Map<String, Long> invalidCountsByReason(long batchId) {
        Map<String, Long> counts = new TreeMap<>();
        for (Object[] row : jdbc.query(SqlStatements.INVALID_COUNTS_BY_REASON,
                (rs, i) -> new Object[]{rs.getString(1), rs.getLong(2)}, batchId)) {
            counts.put((String) row[0], (Long) row[1]);
        }
        return counts;
    }
}
