package com.joseantonio.reconciliation.application.port.out;

import com.joseantonio.reconciliation.application.port.in.DiscrepancyFilter;
import com.joseantonio.reconciliation.application.port.in.DiscrepancyView;
import com.joseantonio.reconciliation.application.port.in.PageQuery;
import com.joseantonio.reconciliation.application.port.in.PageResult;
import com.joseantonio.reconciliation.domain.model.MatchStatus;

import java.math.BigDecimal;
import java.util.Map;

public interface ReconciliationResultPort {

    /**
     * Atomically stores the outcomes AND marks the consumed ledger entries. Fenced by the batch attempt.
     * Throws ReconciliationConflictException if any ledger entry was consumed by someone else meanwhile.
     * Also closes, in earlier batches, the discrepancies that this chunk resolves (see {@link ReconciliationChunk}).
     */
    void saveChunk(ClaimedBatch batch, ReconciliationChunk chunk);

    Map<MatchStatus, Long> countByStatus(long batchId);

    /** Discrepancies of the batch that a LATER batch has since resolved. */
    long countResolved(long batchId);

    /** Sum of |discrepancy amount| per currency, discrepancies only; entries without a computable amount are skipped. */
    Map<String, BigDecimal> discrepancyTotalsByCurrency(long batchId);

    PageResult<DiscrepancyView> findDiscrepancies(long batchId, DiscrepancyFilter filter, PageQuery page);
}
