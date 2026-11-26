package com.joseantonio.reconciliation.infrastructure.persistence.repository;

import com.joseantonio.reconciliation.infrastructure.persistence.entity.ReconciliationBatchEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringDataBatchRepository extends JpaRepository<ReconciliationBatchEntity, Long> {

    Optional<ReconciliationBatchEntity> findBySourceNameAndFileSha256(String sourceName, String fileSha256);
}
