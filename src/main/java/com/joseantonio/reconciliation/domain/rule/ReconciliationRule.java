package com.joseantonio.reconciliation.domain.rule;

import com.joseantonio.reconciliation.domain.model.ReconciliationCandidate;
import com.joseantonio.reconciliation.domain.model.ReconciliationOutcome;

import java.util.Optional;

/**
 * Strategy: decides the outcome of one reference when it is applicable, or abstains.
 * Rules are independent; the {@link ReconciliationEngine} defines their order.
 */
public interface ReconciliationRule {

    String name();

    Optional<ReconciliationOutcome> evaluate(ReconciliationCandidate candidate);
}
