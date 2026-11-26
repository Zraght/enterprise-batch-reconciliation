package com.joseantonio.reconciliation.domain.model;

/**
 * Raw result of an audit rule, before it is bound to a batch and persisted as an alert.
 *
 * @param ledgerTxId the audited ledger movement (natural key of the finding together with the rule); may be null only
 *                   for movements that were never persisted
 */
public record AuditFinding(Long ledgerTxId, ReferenceCode reference, String rule, Severity severity, String description) {
}
