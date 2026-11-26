package com.joseantonio.reconciliation.infrastructure.web.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyVerifierTest {

    private static final String KEY = "0123456789abcdef-demo-key";
    private final ApiKeyVerifier verifier = ApiKeyVerifier.of(KEY, true);

    @Test
    void theRightKeyOpensEveryProtectedPath() {
        assertTrue(verifier.authorize("/api/v1/batches/1/status", KEY));
        assertTrue(verifier.authorize("/api/v1/ledger/seed", KEY));
        assertTrue(verifier.authorize("/anything/else", KEY));
    }

    @Test
    void aMissingOrWrongKeyIsRefusedOnProtectedPaths() {
        assertFalse(verifier.authorize("/api/v1/batches/1/status", null));
        assertFalse(verifier.authorize("/api/v1/batches/1/status", ""));
        assertFalse(verifier.authorize("/api/v1/batches/1/status", KEY + "x"));
        assertFalse(verifier.authorize("/api/v1/batches/1/status", KEY.toUpperCase()));
        assertFalse(verifier.authorize("/api/v1/batches/1/status", KEY.substring(1)));
    }

    @Test
    void denyByDefault_onlyTheDocumentedPathsArePublic() {
        for (String p : new String[]{"/actuator/health", "/actuator/health/liveness", "/swagger-ui.html",
                "/swagger-ui/index.html", "/v3/api-docs", "/v3/api-docs/swagger-config"}) {
            assertTrue(verifier.authorize(p, null), p);
        }
        for (String p : new String[]{"/actuator/info", "/actuator", "/api/v1/batches/1/status", "/error", "/",
                "/swagger-uix", "/v3/api-docs-evil", "/actuator/healthz"}) {
            assertFalse(verifier.authorize(p, null), p);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/swagger-ui/../api/v1/batches/1/status",
            "/v3/api-docs/../../api/v1/ledger/seed",
            "/actuator/health/../info",
            "/swagger-ui//api/v1/x",
            "/actuator/health;jsessionid=1/../../api/v1/x",
            "/%73wagger-ui/x",
            "/swagger-ui\\..\\api"})
    void pathTricksNeverMakeAProtectedPathPublic(String path) {
        assertFalse(verifier.isPublic(path), path);
        assertFalse(verifier.authorize(path, null), path);
    }

    @Test
    void whenDisabledEverythingIsOpen() {
        ApiKeyVerifier open = ApiKeyVerifier.of("  ", false);
        assertFalse(open.enabled());
        assertTrue(open.authorize("/api/v1/batches/1/status", null));
        assertFalse(ApiKeyVerifier.of(null, false).enabled());
    }

    @Test
    void unsafeConfigurationIsRefusedAtStartUp() {
        assertEquals(true, assertThrows(IllegalStateException.class, () -> ApiKeyVerifier.of("", true))
                .getMessage().contains("API_KEY"));
        assertThrows(IllegalStateException.class, () -> ApiKeyVerifier.of(null, true));
        assertThrows(IllegalStateException.class, () -> ApiKeyVerifier.of("short-key", false));
        assertTrue(ApiKeyVerifier.of("x".repeat(ApiKeyVerifier.MIN_KEY_LENGTH), true).enabled());
    }

    @Test
    void theKeyNeverAppearsInTheVerifier() {
        assertFalse(verifier.toString().contains(KEY));
    }
}
