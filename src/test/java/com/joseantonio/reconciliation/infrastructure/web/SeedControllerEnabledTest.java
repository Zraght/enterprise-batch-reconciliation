package com.joseantonio.reconciliation.infrastructure.web;

import com.joseantonio.reconciliation.application.port.in.SeedLedgerUseCase;
import com.joseantonio.reconciliation.application.port.in.SeedResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** With the "test" profile the seed endpoint exists and validates its body. */
@WebMvcTest(controllers = SeedController.class)
@ActiveProfiles("test")
class SeedControllerEnabledTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    SeedLedgerUseCase seed;

    @Test
    void generatesDataAndReturns201() throws Exception {
        when(seed.seed(any())).thenReturn(new SeedResult("NIUBIZ", 42, 102, 110,
                new SeedResult.Expected(90, 4, 3, 2, 2, 5, 2), null, 7L));

        mvc.perform(post("/api/v1/ledger/seed").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceName\":\"NIUBIZ\",\"count\":100,\"randomSeed\":42}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchId").value(7))
                .andExpect(jsonPath("$.expected.matched").value(90))
                .andExpect(jsonPath("$.ledgerEntriesCreated").value(102))
                .andExpect(jsonPath("$.expected.deferredLedgerEntries").value(5));
    }

    @Test
    void invalidFieldsAreRejectedWith400() throws Exception {
        mvc.perform(post("/api/v1/ledger/seed").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceName\":\"\",\"count\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void anInvalidSourceNameIsRejectedWith400() throws Exception {
        when(seed.seed(any())).thenThrow(new com.joseantonio.reconciliation.domain.exception.InvalidRequestException(
                "INVALID_SOURCE", "sourceName must be ..."));
        mvc.perform(post("/api/v1/ledger/seed").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceName\":\"bad\\nname\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SOURCE"));
    }

    @Test
    void unknownFieldsAreRejectedWith400() throws Exception {
        mvc.perform(post("/api/v1/ledger/seed").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceName\":\"X\",\"unknownField\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_BODY"));
    }
}
