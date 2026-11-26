package com.joseantonio.reconciliation.application.port.in;

import java.io.InputStream;
import java.time.Instant;

/**
 * The content stream is consumed (and not closed) by the use case; the caller owns it.
 *
 * @param periodStart optional declared settlement period of the file (start); both ends or none
 * @param periodEnd   optional declared settlement period of the file (end)
 */
public record UploadBatchCommand(String sourceName, String originalFileName, InputStream content,
                                 Instant periodStart, Instant periodEnd) {

    /** Without a declared period: it will be inferred from the settlement timestamps of the file. */
    public UploadBatchCommand(String sourceName, String originalFileName, InputStream content) {
        this(sourceName, originalFileName, content, null, null);
    }
}
