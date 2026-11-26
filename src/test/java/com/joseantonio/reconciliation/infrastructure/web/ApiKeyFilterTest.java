package com.joseantonio.reconciliation.infrastructure.web;

import com.joseantonio.reconciliation.application.port.in.QueryBatchUseCase;
import com.joseantonio.reconciliation.application.port.in.UploadBatchUseCase;
import com.joseantonio.reconciliation.domain.model.BatchPhase;
import com.joseantonio.reconciliation.domain.model.BatchSnapshot;
import com.joseantonio.reconciliation.domain.model.BatchStatus;
import com.joseantonio.reconciliation.domain.model.FileFormat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The API-key gate and the baseline security headers, with a key configured. */
@WebMvcTest(controllers = BatchController.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "reconciliation.security.api-key=0123456789abcdef-test-key")
class ApiKeyFilterTest {

    private static final String KEY = "0123456789abcdef-test-key";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    UploadBatchUseCase upload;

    @MockitoBean
    QueryBatchUseCase query;

    private void stubStatus() {
        when(query.getStatus(5)).thenReturn(new BatchSnapshot(5, Instant.parse("2026-03-10T15:00:00Z"), "NIUBIZ", "s.csv",
                FileFormat.CSV, BatchStatus.PENDING, BatchPhase.QUEUED, 0, 0, 0, 0, null, null, null, null, null, null,
                null, null, Instant.parse("2026-03-10T15:00:00Z"), null, null));
    }

    @Test
    void withoutTheKeyTheApiAnswers401AsProblemJson() throws Exception {
        mvc.perform(get("/api/v1/batches/5/status"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("ApiKey")))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void aWrongKeyIsIndistinguishableFromAMissingOne() throws Exception {
        mvc.perform(get("/api/v1/batches/5/status").header("X-API-Key", KEY + "x"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("A valid X-API-Key header is required"));
    }

    @Test
    void theRightKeyIsAccepted() throws Exception {
        stubStatus();
        mvc.perform(get("/api/v1/batches/5/status").header("X-API-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(5));
    }

    @Test
    void securityHeadersAreAlwaysSetAndApiResponsesAreNotCacheable() throws Exception {
        stubStatus();
        mvc.perform(get("/api/v1/batches/5/status").header("X-API-Key", KEY))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/v1/batches/5/status"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void documentationAndHealthPathsAreNotBehindTheKey() throws Exception {
        // there is no handler for them in this slice, so the answer is 404, but never 401
        mvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
    }
}
