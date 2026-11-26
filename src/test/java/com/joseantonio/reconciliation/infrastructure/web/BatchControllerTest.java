package com.joseantonio.reconciliation.infrastructure.web;

import com.joseantonio.reconciliation.application.port.in.BatchSummary;
import com.joseantonio.reconciliation.application.port.in.DiscrepancyView;
import com.joseantonio.reconciliation.application.port.in.PageQuery;
import com.joseantonio.reconciliation.application.port.in.PageResult;
import com.joseantonio.reconciliation.application.port.in.QueryBatchUseCase;
import com.joseantonio.reconciliation.application.port.in.UploadBatchCommand;
import com.joseantonio.reconciliation.application.port.in.UploadBatchUseCase;
import com.joseantonio.reconciliation.application.port.in.UploadResult;
import com.joseantonio.reconciliation.domain.exception.BatchNotCompletedException;
import com.joseantonio.reconciliation.domain.exception.BatchNotFoundException;
import com.joseantonio.reconciliation.domain.exception.InvalidRequestException;
import com.joseantonio.reconciliation.domain.model.BatchPhase;
import com.joseantonio.reconciliation.domain.model.BatchSnapshot;
import com.joseantonio.reconciliation.domain.model.BatchStatus;
import com.joseantonio.reconciliation.domain.model.FileFormat;
import com.joseantonio.reconciliation.domain.model.MatchStatus;
import com.joseantonio.reconciliation.domain.model.Severity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Web slice: HTTP contract, status codes and error format. Use cases are mocked. */
@WebMvcTest(controllers = BatchController.class)
@ActiveProfiles("test")
class BatchControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    UploadBatchUseCase upload;

    @MockitoBean
    QueryBatchUseCase query;

    private static BatchSnapshot snapshot(long id, BatchStatus status) {
        return new BatchSnapshot(id, Instant.parse("2026-03-10T15:00:00Z"), "NIUBIZ", "s.csv", FileFormat.CSV, status,
                BatchPhase.QUEUED, 0, 0, 0, 0, null, null, null, null, null, null, null, null,
                Instant.parse("2026-03-10T15:00:00Z"), null, null);
    }

    private static MockMultipartFile csv() {
        return new MockMultipartFile("file", "s.csv", "text/csv", "external_ref,amount\nA,1".getBytes());
    }

    @Test
    void uploadReturns202WithLocationAndBatchId() throws Exception {
        when(upload.upload(any())).thenReturn(new UploadResult(snapshot(42, BatchStatus.PENDING), UploadResult.Outcome.CREATED));

        mvc.perform(multipart("/api/v1/batches/upload").file(csv()).param("sourceName", "NIUBIZ"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/batches/42/status"))
                .andExpect(jsonPath("$.batchId").value(42))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.outcome").value("CREATED"));

        ArgumentCaptor<UploadBatchCommand> captor = ArgumentCaptor.forClass(UploadBatchCommand.class);
        verify(upload).upload(captor.capture());
        assertEquals("NIUBIZ", captor.getValue().sourceName());
        assertEquals("s.csv", captor.getValue().originalFileName());
    }

    @Test
    void replayOfAnAlreadyRegisteredFileReturns200() throws Exception {
        when(upload.upload(any())).thenReturn(new UploadResult(snapshot(42, BatchStatus.COMPLETED), UploadResult.Outcome.EXISTING));

        mvc.perform(multipart("/api/v1/batches/upload").file(csv()).param("sourceName", "NIUBIZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("EXISTING"));
    }

    @Test
    void retriedBatchReturns202() throws Exception {
        when(upload.upload(any())).thenReturn(new UploadResult(snapshot(7, BatchStatus.PENDING), UploadResult.Outcome.RETRIED));
        mvc.perform(multipart("/api/v1/batches/upload").file(csv()).param("sourceName", "NIUBIZ"))
                .andExpect(status().isAccepted());
    }

    @Test
    void uploadWithoutFileOrSourceIsABadRequestWithProblemDetails() throws Exception {
        mvc.perform(multipart("/api/v1/batches/upload").param("sourceName", "NIUBIZ"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/batches/upload").file(csv()))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/batches/upload").file(csv()).param("sourceName", "  "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void domainRejectionOfTheFileIsMappedTo400() throws Exception {
        when(upload.upload(any())).thenThrow(new InvalidRequestException("UNSUPPORTED_FILE_FORMAT", "Only .csv and .json"));
        mvc.perform(multipart("/api/v1/batches/upload").file(csv()).param("sourceName", "NIUBIZ"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_FORMAT"))
                .andExpect(jsonPath("$.title").value("Invalid request"));
    }

    @Test
    void statusIsReturnedForKnownBatchesAnd404ForUnknownOnes() throws Exception {
        when(query.getStatus(5)).thenReturn(snapshot(5, BatchStatus.PROCESSING));
        when(query.getStatus(6)).thenThrow(new BatchNotFoundException(6));

        mvc.perform(get("/api/v1/batches/5/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(5))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.progressPercent").value(0));
        mvc.perform(get("/api/v1/batches/6/status"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BATCH_NOT_FOUND"));
        mvc.perform(get("/api/v1/batches/not-a-number/status"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void summaryExposesAllRequiredMetrics() throws Exception {
        var summary = new BatchSummary(5, BatchStatus.COMPLETED, "NIUBIZ", 8, 6, 2,
                Map.of("INVALID_AMOUNT", 1L, "DUPLICATE_REFERENCE_IDENTICAL", 1L),
                Instant.parse("2026-03-10T15:00:00Z"), Instant.parse("2026-03-11T06:31:00Z"),
                com.joseantonio.reconciliation.domain.model.SettlementWindow.Source.INFERRED,
                Instant.parse("2026-03-10T06:31:00Z"),
                7, 3, 4, 1, 3,
                Map.of(MatchStatus.AMOUNT_MISMATCH, 2L, MatchStatus.MISSING_EXTERNAL, 1L, MatchStatus.MISSING_INTERNAL, 1L),
                Map.of("PEN", new BigDecimal("85.5000")), new BigDecimal("42.86"), 2, 2L,
                Map.of(Severity.HIGH, 2L), Instant.parse("2026-03-10T15:00:00Z"), Instant.parse("2026-03-10T15:01:00Z"));
        when(query.getSummary(5)).thenReturn(summary);

        mvc.perform(get("/api/v1/batches/5/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRecords").value(8))
                .andExpect(jsonPath("$.matchedTransactions").value(3))
                .andExpect(jsonPath("$.discrepancyCount").value(4))
                .andExpect(jsonPath("$.totalDiscrepancyAmount.PEN").value(85.5))
                .andExpect(jsonPath("$.reconciliationRatePercent").value(42.86))
                .andExpect(jsonPath("$.alertsGenerated").value(2))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.discrepanciesByStatus.AMOUNT_MISMATCH").value(2))
                .andExpect(jsonPath("$.resolvedByLaterBatches").value(1))
                .andExpect(jsonPath("$.openDiscrepancies").value(3))
                .andExpect(jsonPath("$.deferredLedgerEntries").value(2))
                .andExpect(jsonPath("$.periodSource").value("INFERRED"))
                .andExpect(jsonPath("$.invalidRecordsByReason.INVALID_AMOUNT").value(1));
    }

    @Test
    void summaryOfAnUnfinishedBatchIs409() throws Exception {
        when(query.getSummary(9)).thenThrow(new BatchNotCompletedException(9, BatchStatus.PROCESSING));
        mvc.perform(get("/api/v1/batches/9/summary"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_NOT_COMPLETED"))
                .andExpect(jsonPath("$.batchStatus").value("PROCESSING"));
    }

    @Test
    void discrepanciesArePagedAndFilterParametersReachTheUseCase() throws Exception {
        var row = new DiscrepancyView(1, "REF-004", MatchStatus.MISSING_EXTERNAL, new BigDecimal("-75.0000"), "PEN", 4L, null, "d", 9L);
        var query1 = new PageQuery(1, 10);
        when(query.getDiscrepancies(eq(5L), any(), eq(query1))).thenReturn(PageResult.of(List.of(row), query1, 11));

        mvc.perform(get("/api/v1/batches/5/discrepancies")
                        .param("matchStatus", "MISSING_EXTERNAL").param("reference", "ref").param("currency", "PEN")
                        .param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].referenceCode").value("REF-004"))
                .andExpect(jsonPath("$.content[0].discrepancyAmount").value(-75.0))
                .andExpect(jsonPath("$.content[0].resolvedByBatchId").value(9))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalElements").value(11))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void invalidPagingOrFiltersAreBadRequests() throws Exception {
        mvc.perform(get("/api/v1/batches/5/discrepancies").param("size", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/batches/5/discrepancies").param("size", "501")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/batches/5/discrepancies").param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/batches/5/discrepancies").param("matchStatus", "NOPE")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/batches/5/discrepancies").param("matchStatus", "MATCHED")).andExpect(status().isBadRequest());
    }

    @Test
    void unsupportedMethodKeepsItsOwnStatusInsteadOfBecoming500() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/batches/5/status")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void aDeclaredSettlementPeriodReachesTheUseCase() throws Exception {
        when(upload.upload(any())).thenReturn(new UploadResult(snapshot(42, BatchStatus.PENDING), UploadResult.Outcome.CREATED));

        mvc.perform(multipart("/api/v1/batches/upload").file(csv()).param("sourceName", "NIUBIZ")
                        .param("periodStart", "2026-03-10T00:00:00Z").param("periodEnd", "2026-03-10T23:59:59Z"))
                .andExpect(status().isAccepted());

        ArgumentCaptor<UploadBatchCommand> captor = ArgumentCaptor.forClass(UploadBatchCommand.class);
        verify(upload).upload(captor.capture());
        assertEquals(Instant.parse("2026-03-10T00:00:00Z"), captor.getValue().periodStart());
        assertEquals(Instant.parse("2026-03-10T23:59:59Z"), captor.getValue().periodEnd());
    }

    @Test
    void anUnparseablePeriodIsABadRequest() throws Exception {
        mvc.perform(multipart("/api/v1/batches/upload").file(csv()).param("sourceName", "NIUBIZ")
                        .param("periodStart", "yesterday").param("periodEnd", "today"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aPeriodConflictOnAnExistingBatchIs409() throws Exception {
        when(upload.upload(any())).thenThrow(new com.joseantonio.reconciliation.domain.exception.BatchConflictException(
                "BATCH_PERIOD_CONFLICT", "already registered with another settlement period"));
        mvc.perform(multipart("/api/v1/batches/upload").file(csv()).param("sourceName", "NIUBIZ")
                        .param("periodStart", "2026-03-10T00:00:00Z").param("periodEnd", "2026-03-10T23:59:59Z"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_PERIOD_CONFLICT"));
    }

    @Test
    void theResolvedFilterReachesTheUseCase() throws Exception {
        var q = new PageQuery(0, 50);
        when(query.getDiscrepancies(eq(5L), any(), eq(q))).thenReturn(PageResult.of(List.of(), q, 0));
        mvc.perform(get("/api/v1/batches/5/discrepancies").param("resolved", "false")).andExpect(status().isOk());

        ArgumentCaptor<com.joseantonio.reconciliation.application.port.in.DiscrepancyFilter> captor =
                ArgumentCaptor.forClass(com.joseantonio.reconciliation.application.port.in.DiscrepancyFilter.class);
        verify(query).getDiscrepancies(eq(5L), captor.capture(), eq(q));
        assertEquals(Boolean.FALSE, captor.getValue().resolved());
    }

    @Test
    void nonPositiveBatchIdsAreRejectedBeforeReachingTheUseCase() throws Exception {
        mvc.perform(get("/api/v1/batches/0/status")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/batches/-3/summary")).andExpect(status().isBadRequest());
    }

    @Test
    void errorResponsesNeverExposeInternals() throws Exception {
        when(query.getStatus(7)).thenThrow(new IllegalStateException("jdbc:sqlserver://db01:1433;password=s3cr3t"));
        mvc.perform(get("/api/v1/batches/7/status"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("s3cr3t"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("db01"))));
    }
}
