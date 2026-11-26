package com.joseantonio.reconciliation.infrastructure.persistence.adapter;

import com.joseantonio.reconciliation.application.port.in.DiscrepancyFilter;
import com.joseantonio.reconciliation.application.port.in.DiscrepancyView;
import com.joseantonio.reconciliation.application.port.in.PageQuery;
import com.joseantonio.reconciliation.application.port.in.PageResult;
import com.joseantonio.reconciliation.application.port.out.ClaimedBatch;
import com.joseantonio.reconciliation.application.port.out.ReconciliationChunk;
import com.joseantonio.reconciliation.application.port.out.ReconciliationResultPort;
import com.joseantonio.reconciliation.domain.exception.ReconciliationConflictException;
import com.joseantonio.reconciliation.domain.model.BatchPhase;
import com.joseantonio.reconciliation.domain.model.MatchStatus;
import com.joseantonio.reconciliation.domain.model.ReconciliationOutcome;
import com.joseantonio.reconciliation.infrastructure.persistence.BatchOwnershipGuard;
import com.joseantonio.reconciliation.infrastructure.persistence.SqlStatements;
import com.joseantonio.reconciliation.infrastructure.persistence.entity.ReconciliationResultEntity;
import com.joseantonio.reconciliation.infrastructure.persistence.repository.SpringDataResultRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Component
public class ResultPersistenceAdapter implements ReconciliationResultPort {

    private static final int MAX_DETAIL = 500;

    private final JdbcTemplate jdbc;
    private final BatchOwnershipGuard guard;
    private final SpringDataResultRepository repository;

    public ResultPersistenceAdapter(JdbcTemplate jdbc, BatchOwnershipGuard guard, SpringDataResultRepository repository) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.repository = repository;
    }

    /**
     * Results and the "consumed" marks of the ledger commit atomically. If any ledger entry was taken by someone
     * else in the meantime the whole chunk rolls back (no result may exist for an entry we did not actually pair).
     */
    @Override
    @Transactional
    public void saveChunk(ClaimedBatch batch, ReconciliationChunk chunk) {
        guard.heartbeat(batch, BatchPhase.RECONCILIATION, chunk.progressPercent());

        if (!chunk.outcomes().isEmpty()) {
            jdbc.batchUpdate(SqlStatements.INSERT_RESULT, chunk.outcomes(), chunk.outcomes().size(), (ps, o) -> {
                ps.setLong(1, batch.batchId());
                ps.setString(2, o.reference().value());
                ps.setString(3, o.status().name());
                ps.setBigDecimal(4, o.discrepancyAmount());
                ps.setString(5, o.currency() == null ? null : o.currency().getCurrencyCode());
                setNullableLong(ps, 6, o.ledgerTxId());
                setNullableLong(ps, 7, o.settlementId());
                ps.setString(8, truncate(o.detail()));
            });
        }
        if (!chunk.consumedLedgerTxIds().isEmpty()) {
            List<Long> ids = new ArrayList<>(chunk.consumedLedgerTxIds());
            int[][] counts = jdbc.batchUpdate(SqlStatements.CONSUME_LEDGER, ids, ids.size(), (ps, txId) -> {
                ps.setLong(1, batch.batchId());
                ps.setLong(2, txId);
            });
            for (int[] group : counts) {
                for (int count : group) {
                    if (count == 0) { // negative values mean "success, count unknown" and are accepted
                        throw new ReconciliationConflictException(
                                "A ledger entry was reconciled concurrently by another batch; chunk rolled back");
                    }
                }
            }
            // Late settlement: the movements just paired may have been reported MISSING_EXTERNAL by earlier batches.
            jdbc.batchUpdate(SqlStatements.RESOLVE_MISSING_EXTERNAL, ids, ids.size(), (ps, txId) -> {
                ps.setLong(1, batch.batchId());
                ps.setLong(2, txId);
                ps.setLong(3, batch.batchId());
            });
        }
        if (!chunk.resolvedSettlementIds().isEmpty()) {
            // Late booking: the settlements just paired were reported MISSING_INTERNAL by an earlier batch.
            List<Long> settlementIds = new ArrayList<>(chunk.resolvedSettlementIds());
            jdbc.batchUpdate(SqlStatements.RESOLVE_MISSING_INTERNAL, settlementIds, settlementIds.size(), (ps, id) -> {
                ps.setLong(1, batch.batchId());
                ps.setLong(2, id);
                ps.setLong(3, batch.batchId());
            });
        }
    }

    private static void setNullableLong(java.sql.PreparedStatement ps, int index, Long value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static String truncate(String detail) {
        return detail == null || detail.length() <= MAX_DETAIL ? detail : detail.substring(0, MAX_DETAIL);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<MatchStatus, Long> countByStatus(long batchId) {
        Map<MatchStatus, Long> counts = new EnumMap<>(MatchStatus.class);
        for (Object[] row : repository.countGroupedByStatus(batchId)) {
            counts.put(MatchStatus.valueOf((String) row[0]), ((Number) row[1]).longValue());
        }
        return counts;
    }

    @Override
    @Transactional(readOnly = true)
    public long countResolved(long batchId) {
        return repository.countResolved(batchId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, BigDecimal> discrepancyTotalsByCurrency(long batchId) {
        Map<String, BigDecimal> totals = new TreeMap<>();
        for (Object[] row : repository.discrepancyTotalsGroupedByCurrency(batchId)) {
            totals.put((String) row[0], (BigDecimal) row[1]);
        }
        return totals;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<DiscrepancyView> findDiscrepancies(long batchId, DiscrepancyFilter filter, PageQuery query) {
        Specification<ReconciliationResultEntity> spec = (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("batchId"), batchId));
            if (filter.matchStatus() != null) {
                predicates.add(cb.equal(root.get("matchStatus"), filter.matchStatus().name()));
            } else {
                predicates.add(cb.notEqual(root.get("matchStatus"), MatchStatus.MATCHED.name()));
            }
            if (filter.referenceContains() != null) {
                predicates.add(cb.like(root.<String>get("referenceCode"),
                        "%" + escapeLike(filter.referenceContains()) + "%", '\\'));
            }
            if (filter.currency() != null) {
                predicates.add(cb.equal(root.get("currency"), filter.currency()));
            }
            if (filter.resolved() != null) {
                predicates.add(filter.resolved()
                        ? cb.isNotNull(root.get("resolvedByBatchId"))
                        : cb.isNull(root.get("resolvedByBatchId")));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<ReconciliationResultEntity> page =
                repository.findAll(spec, PageRequest.of(query.page(), query.size(), Sort.by("resultId")));
        List<DiscrepancyView> content = page.getContent().stream().map(ResultPersistenceAdapter::toView).toList();
        return new PageResult<>(content, query.page(), query.size(), page.getTotalElements(), page.getTotalPages());
    }

    private static DiscrepancyView toView(ReconciliationResultEntity e) {
        return new DiscrepancyView(e.getResultId(), e.getReferenceCode(), MatchStatus.valueOf(e.getMatchStatus()),
                e.getDiscrepancyAmount(), e.getCurrency(), e.getLedgerTxId(), e.getSettlementId(), e.getDetail(),
                e.getResolvedByBatchId());
    }

    /** User input goes into a LIKE pattern: neutralise the wildcards. */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_").replace("[", "\\[");
    }
}
