package com.joseantonio.reconciliation.infrastructure.persistence.repository;

import com.joseantonio.reconciliation.infrastructure.persistence.entity.AuditAlertEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SpringDataAlertRepository extends JpaRepository<AuditAlertEntity, Long> {

    /** Rows: [severity (String), count (Long)]. */
    @Query("select a.severity, count(a) from AuditAlertEntity a where a.batchId = :batchId group by a.severity")
    List<Object[]> countGroupedBySeverity(@Param("batchId") Long batchId);
}
