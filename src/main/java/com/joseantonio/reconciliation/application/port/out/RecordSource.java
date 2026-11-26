package com.joseantonio.reconciliation.application.port.out;

import java.util.Set;

/** Forward-only stream of raw records. Must be closed. */
public interface RecordSource extends AutoCloseable {

    /** Next record, or {@code null} at the end. Throws FileProcessingException on structural errors. */
    RawRecord next();

    /** Column names declared by the file (CSV header); empty for formats without a header. */
    Set<String> columns();

    long bytesRead();

    long totalBytes();

    @Override
    void close();
}
