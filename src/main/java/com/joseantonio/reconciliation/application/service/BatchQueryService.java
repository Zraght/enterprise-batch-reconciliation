package com.joseantonio.reconciliation.application.service;

import com.joseantonio.reconciliation.application.port.in.BatchSummary;
import com.joseantonio.reconciliation.application.port.in.DiscrepancyFilter;
import com.joseantonio.reconciliation.application.port.in.DiscrepancyView;
import com.joseantonio.reconciliation.application.port.in.PageQuery;
import com.joseantonio.reconciliation.application.port.in.PageResult;
import com.joseantonio.reconciliation.application.port.in.QueryBatchUseCase;
import com.joseantonio.reconciliation.application.port.out.AuditAlertPort;
import com.joseantonio.reconciliation.application.port.out.BatchRepositoryPort;
import com.joseantonio.reconciliation.application.port.out.ReconciliationResultPort;
import com.joseantonio.reconciliation.application.port.out.SettlementStorePort;
import com.joseantonio.reconciliation.domain.exception.BatchNotCompletedException;
import com.joseantonio.reconciliation.domain.exception.BatchNotFoundException;
import com.joseantonio.reconciliation.domain.model.BatchSnapshot;
import com.joseantonio.reconciliation.domain.model.BatchStatus;
import com.joseantonio.reconciliation.domain.model.MatchStatus;
import com.joseantonio.reconciliation.domain.model.ReconciliationMetrics;
import com.joseantonio.reconciliation.domain.model.Severity;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;

public final class BatchQueryService implements QueryBatchUseCase {

    private final BatchRepositoryPort batches;
    private final ReconciliationResultPort results;
    private final AuditAlertPort alerts;
    private final SettlementStorePort settlements;

    public BatchQueryService(BatchRepositoryPort batches, ReconciliationResultPort results, AuditAlertPort alerts,
                             SettlementStorePort settlements) {
        this.batches = batches;
        this.results = results;
        this.alerts = alerts;
        this.settlements = settlements;
    }

    @Override
    public BatchSnapshot getStatus(long batchId) {
        return batches.findById(batchId).orElseThrow(() -> new BatchNotFoundException(batchId));
    }

    @Override
    public BatchSummary getSummary(long batchId) {
        BatchSnapshot batch = requireCompleted(batchId);

        Map<MatchStatus, Long> counts = results.countByStatus(batchId);
        long matched = counts.getOrDefault(MatchStatus.MATCHED, 0L);
        long evaluated = counts.values().stream().mapToLong(Long::longValue).sum();
        long resolved = results.countResolved(batchId);

        Map<MatchStatus, Long> discrepancies = new EnumMap<>(MatchStatus.class);
        for (MatchStatus status : MatchStatus.values()) {
            if (status != MatchStatus.MATCHED) {
                discrepancies.put(status, counts.getOrDefault(status, 0L));
            }
        }
        Map<String, BigDecimal> totals = new TreeMap<>(results.discrepancyTotalsByCurrency(batchId));

        Map<Severity, Long> bySeverity = new EnumMap<>(Severity.class);
        Map<Severity, Long> stored = alerts.countBySeverity(batchId);
        for (Severity severity : Severity.values()) {
            bySeverity.put(severity, stored.getOrDefault(severity, 0L));
        }
        long alertsTotal = bySeverity.values().stream().mapToLong(Long::longValue).sum();

        Map<String, Long> invalidByReason = new TreeMap<>(settlements.invalidCountsByReason(batchId));

        long discrepancyCount = evaluated - matched;
        return new BatchSummary(batch.batchId(), batch.status(), batch.sourceName(),
                batch.totalRecords(), batch.validRecords(), batch.invalidRecords(), invalidByReason,
                batch.periodStart(), batch.periodEnd(), batch.periodSource(), batch.dueCutoff(),
                evaluated, matched, discrepancyCount, resolved, discrepancyCount - resolved,
                discrepancies, totals, ReconciliationMetrics.reconciliationRate(matched, evaluated),
                batch.deferredLedgerEntries() == null ? 0 : batch.deferredLedgerEntries(),
                alertsTotal, bySeverity, batch.startedAt(), batch.completedAt());
    }

    @Override
    public PageResult<DiscrepancyView> getDiscrepancies(long batchId, DiscrepancyFilter filter, PageQuery page) {
        requireCompleted(batchId);
        return results.findDiscrepancies(batchId, filter == null ? DiscrepancyFilter.none() : filter, page);
    }

    private BatchSnapshot requireCompleted(long batchId) {
        BatchSnapshot batch = getStatus(batchId);
        if (batch.status() != BatchStatus.COMPLETED) {
            throw new BatchNotCompletedException(batchId, batch.status());
        }
        return batch;
    }
}
