package com.joseantonio.reconciliation.infrastructure.web;

import com.joseantonio.reconciliation.application.port.in.SeedLedgerUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Under any profile other than dev/test (here "prod") the seed endpoint is not registered at all. */
@WebMvcTest(controllers = SeedController.class)
@ActiveProfiles("prod")
@TestPropertySource(properties = "reconciliation.security.api-key=0123456789abcdef-prod-test-key")
class SeedControllerDisabledInProdTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    SeedLedgerUseCase seed;

    @Test
    void endpointDoesNotExist() throws Exception {
        mvc.perform(post("/api/v1/ledger/seed")
                        .header("X-API-Key", "0123456789abcdef-prod-test-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceName\":\"NIUBIZ\"}"))
                .andExpect(status().isNotFound());
    }
}
