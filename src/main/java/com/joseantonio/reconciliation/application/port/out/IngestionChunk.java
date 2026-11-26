package com.joseantonio.reconciliation.application.port.out;

import com.joseantonio.reconciliation.domain.model.ExternalSettlement;
import com.joseantonio.reconciliation.domain.model.InvalidRecord;

import java.util.List;

/** One atomic unit of ingestion: persisted together with the batch counters in a single transaction. */
public record IngestionChunk(
        List<ExternalSettlement> valid,
        List<InvalidRecord> invalid,
        int recordsRead,
        int progressPercent) {

    public IngestionChunk {
        valid = List.copyOf(valid);
        invalid = List.copyOf(invalid);
    }
}
