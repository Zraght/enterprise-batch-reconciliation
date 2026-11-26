package com.joseantonio.reconciliation.infrastructure.batch;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Counts the bytes consumed from the underlying stream; used to estimate ingestion progress. */
final class CountingInputStream extends FilterInputStream {

    private long count;

    CountingInputStream(InputStream in) {
        super(in);
    }

    long count() {
        return count;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) {
            count++;
        }
        return b;
    }

    @Override
    public int read(byte[] buffer, int off, int len) throws IOException {
        int n = super.read(buffer, off, len);
        if (n > 0) {
            count += n;
        }
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        long skipped = super.skip(n);
        if (skipped > 0) {
            count += skipped;
        }
        return skipped;
    }

    @Override
    public boolean markSupported() {
        return false;
    }
}
