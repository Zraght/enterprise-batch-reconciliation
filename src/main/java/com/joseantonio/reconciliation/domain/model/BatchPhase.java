package com.joseantonio.reconciliation.domain.model;

/** Coarse processing phase, used to report progress. */
public enum BatchPhase { QUEUED, INGESTION, RECONCILIATION, AUDIT, DONE }
