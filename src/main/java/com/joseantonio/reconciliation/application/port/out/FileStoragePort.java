package com.joseantonio.reconciliation.application.port.out;

import java.io.InputStream;

public interface FileStoragePort {

    /** Streams the content to durable storage computing its SHA-256 on the fly (never loads it in memory). */
    StoredFile store(InputStream content, String originalFileName);

    /** Best effort removal; never throws. */
    void delete(String location);
}
