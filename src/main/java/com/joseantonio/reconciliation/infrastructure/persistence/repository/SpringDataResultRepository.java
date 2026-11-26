package com.joseantonio.reconciliation.infrastructure.persistence.repository;

import com.joseantonio.reconciliation.infrastructure.persistence.entity.ReconciliationResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SpringDataResultRepository
        extends JpaRepository<ReconciliationResultEntity, Long>, JpaSpecificationExecutor<ReconciliationResultEntity> {

    /** Rows: [matchStatus (String), count (Long)]. */
    @Query("select r.matchStatus, count(r) from ReconciliationResultEntity r "
            + "where r.batchId = :batchId group by r.matchStatus")
    List<Object[]> countGroupedByStatus(@Param("batchId") Long batchId);

    /** Discrepancies of the batch that a later batch has resolved. */
    @Query("select count(r) from ReconciliationResultEntity r "
            + "where r.batchId = :batchId and r.matchStatus <> 'MATCHED' and r.resolvedByBatchId is not null")
    long countResolved(@Param("batchId") Long batchId);

    /** Rows: [currency (String), sum of |discrepancy| (BigDecimal)]. Entries without a computable amount are skipped. */
    @Query("select r.currency, sum(abs(r.discrepancyAmount)) from ReconciliationResultEntity r "
            + "where r.batchId = :batchId and r.matchStatus <> 'MATCHED' and r.discrepancyAmount is not null "
            + "group by r.currency")
    List<Object[]> discrepancyTotalsGroupedByCurrency(@Param("batchId") Long batchId);
}
