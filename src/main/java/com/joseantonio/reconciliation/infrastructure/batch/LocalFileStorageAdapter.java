package com.joseantonio.reconciliation.infrastructure.batch;

import com.joseantonio.reconciliation.application.port.out.FileStoragePort;
import com.joseantonio.reconciliation.application.port.out.StoredFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * Stores uploads on the local file system under a server-generated name (the client-supplied name is never used
 * as a path, which rules out path traversal) while computing the SHA-256 in a single streaming pass.
 */
public final class LocalFileStorageAdapter implements FileStoragePort {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorageAdapter.class);
    private static final int BUFFER = 64 * 1024;

    private final Path directory;

    public LocalFileStorageAdapter(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create the storage directory " + this.directory, e);
        }
    }

    @Override
    public StoredFile store(InputStream content, String originalFileName) {
        Path target = directory.resolve(UUID.randomUUID() + "." + extensionOf(originalFileName));
        MessageDigest digest = newDigest();
        long size = 0;
        try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[BUFFER];
            int read;
            while ((read = content.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
                size += read;
            }
        } catch (IOException e) {
            delete(target.toString());
            throw new UncheckedIOException("Could not store the uploaded file", e);
        }
        return new StoredFile(target.toString(), HexFormat.of().formatHex(digest.digest()), size);
    }

    @Override
    public void delete(String location) {
        try {
            Files.deleteIfExists(Path.of(location));
        } catch (IOException | RuntimeException e) {
            log.warn("Could not delete stored file {}", location, e);
        }
    }

    private static String extensionOf(String name) {
        if (name != null) {
            String lower = name.toLowerCase(Locale.ROOT).trim();
            if (lower.endsWith(".csv")) {
                return "csv";
            }
            if (lower.endsWith(".json")) {
                return "json";
            }
        }
        return "bin";
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JRE", e);
        }
    }
}
