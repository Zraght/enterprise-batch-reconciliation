package com.joseantonio.reconciliation.application.port.out;

import com.joseantonio.reconciliation.domain.model.ExternalSettlement;

import java.util.List;
import java.util.Map;

public interface SettlementStorePort {

    /** Atomically stores the chunk and updates the batch counters. Fenced by the batch attempt. */
    void saveChunk(ClaimedBatch batch, IngestionChunk chunk);

    /** Keyset pagination over the batch settlements, ascending by id. */
    List<ExternalSettlement> findAfter(long batchId, long settlementIdExclusive, int limit);

    /** Why records of the batch were rejected: reason code -> count. */
    Map<String, Long> invalidCountsByReason(long batchId);
}
