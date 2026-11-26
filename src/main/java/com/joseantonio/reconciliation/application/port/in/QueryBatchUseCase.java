package com.joseantonio.reconciliation.application.port.in;

import com.joseantonio.reconciliation.domain.model.BatchSnapshot;

public interface QueryBatchUseCase {

    BatchSnapshot getStatus(long batchId);

    BatchSummary getSummary(long batchId);

    PageResult<DiscrepancyView> getDiscrepancies(long batchId, DiscrepancyFilter filter, PageQuery page);
}
