package com.joseantonio.reconciliation.infrastructure.web.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Decision logic of the API-key protection (framework free, so it is unit-tested without a container).
 *
 * <ul>
 *   <li><b>Deny by default</b>: every path needs the key except a short allow-list (health probe, API documentation).</li>
 *   <li>The key is compared through SHA-256 digests with {@link MessageDigest#isEqual}, which is constant-time and does
 *       not depend on the length of what the caller sends.</li>
 *   <li>The path given to {@link #authorize} must be the container's DECODED and NORMALISED path (servlet path + path
 *       info), never the raw request URI: {@code /swagger-ui/../api/...} or {@code /%61pi/...} must not be mistaken for a
 *       public path. As a second line of defence a path that still contains dot segments, double slashes, path
 *       parameters or percent signs is never public.</li>
 *   <li>A configured key shorter than {@value #MIN_KEY_LENGTH} characters is refused at start-up.</li>
 * </ul>
 * This is a pragmatic shared-secret gate suitable for a demonstration or an internal service behind a gateway; it is not a
 * replacement for OAuth2/OIDC with per-user identity and roles.
 */
public final class ApiKeyVerifier {

    public static final String HEADER = "X-API-Key";
    public static final int MIN_KEY_LENGTH = 16;

    private static final List<String> PUBLIC_PATHS = List.of(
            "/actuator/health", "/swagger-ui", "/swagger-ui.html", "/v3/api-docs");

    private final byte[] expectedDigest; // null => authentication disabled

    private ApiKeyVerifier(byte[] expectedDigest) {
        this.expectedDigest = expectedDigest;
    }

    /**
     * @param configuredKey the shared secret, or blank to disable authentication
     * @param required      when true a blank key is a configuration error
     * @throws IllegalStateException when the configuration is unsafe
     */
    public static ApiKeyVerifier of(String configuredKey, boolean required) {
        boolean blank = configuredKey == null || configuredKey.isBlank();
        if (blank) {
            if (required) {
                throw new IllegalStateException("reconciliation.security.api-key is required in this profile: "
                        + "set the API_KEY environment variable (at least " + MIN_KEY_LENGTH + " characters)");
            }
            return new ApiKeyVerifier(null);
        }
        if (configuredKey.length() < MIN_KEY_LENGTH) {
            throw new IllegalStateException("The API key must have at least " + MIN_KEY_LENGTH + " characters");
        }
        return new ApiKeyVerifier(sha256(configuredKey));
    }

    public boolean enabled() {
        return expectedDigest != null;
    }

    /** @param path decoded, normalised path (servlet path + path info) */
    public boolean isPublic(String path) {
        if (path == null || path.isEmpty() || path.contains("..") || path.contains("//")
                || path.contains(";") || path.contains("%") || path.contains("\\")) {
            return false;
        }
        for (String publicPath : PUBLIC_PATHS) {
            if (path.equals(publicPath) || path.startsWith(publicPath + "/")) {
                return true;
            }
        }
        return false;
    }

    public boolean authorize(String path, String presentedKey) {
        if (!enabled() || isPublic(path)) {
            return true;
        }
        if (presentedKey == null) {
            return false;
        }
        return MessageDigest.isEqual(sha256(presentedKey), expectedDigest);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JRE", e);
        }
    }
}
