package com.joseantonio.reconciliation.infrastructure.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The whole application with an API key configured: nothing under /api works without it; health stays public. */
@AutoConfigureMockMvc
@TestPropertySource(properties = "reconciliation.security.api-key=integration-test-api-key")
class ApiKeySecurityIT extends AbstractSqlServerIT {

    private static final String KEY = "integration-test-api-key";

    @Autowired
    MockMvc mvc;

    @Test
    void unauthenticatedRequestsAreRefusedAndHaveNoSideEffects() throws Exception {
        String source = uniqueSource("SEC");
        mvc.perform(multipart("/api/v1/batches/upload")
                        .file(new MockMultipartFile("file", "s.csv", "text/csv",
                                "external_ref,amount,currency,settlement_timestamp\nA,1,PEN,2026-03-10T15:00:00Z\n"
                                        .getBytes(StandardCharsets.UTF_8)))
                        .param("sourceName", source))
                .andExpect(status().isUnauthorized());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_batches WHERE source_name = ?",
                Integer.class, source));
        mvc.perform(post("/api/v1/ledger/seed").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceName\":\"" + source + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/batches/1/status")).andExpect(status().isUnauthorized());
    }

    @Test
    void theKeyUnlocksTheApiAndHealthIsAlwaysPublic() throws Exception {
        mvc.perform(get("/api/v1/batches/987654321/status").header("X-API-Key", KEY)).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }
}
