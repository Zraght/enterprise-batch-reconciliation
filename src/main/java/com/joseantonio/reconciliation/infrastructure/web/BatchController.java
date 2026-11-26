package com.joseantonio.reconciliation.infrastructure.web;

import com.joseantonio.reconciliation.application.port.in.DiscrepancyFilter;
import com.joseantonio.reconciliation.application.port.in.PageQuery;
import com.joseantonio.reconciliation.application.port.in.QueryBatchUseCase;
import com.joseantonio.reconciliation.application.port.in.UploadBatchCommand;
import com.joseantonio.reconciliation.application.port.in.UploadBatchUseCase;
import com.joseantonio.reconciliation.application.port.in.UploadResult;
import com.joseantonio.reconciliation.domain.model.MatchStatus;
import com.joseantonio.reconciliation.infrastructure.web.dto.BatchStatusResponse;
import com.joseantonio.reconciliation.infrastructure.web.dto.BatchSummaryResponse;
import com.joseantonio.reconciliation.infrastructure.web.dto.DiscrepancyResponse;
import com.joseantonio.reconciliation.infrastructure.web.dto.PagedResponse;
import com.joseantonio.reconciliation.infrastructure.web.dto.UploadResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/batches")
@Validated
@Tag(name = "Batches", description = "Upload settlement files and follow their reconciliation")
public class BatchController {

    private final UploadBatchUseCase uploadUseCase;
    private final QueryBatchUseCase queryUseCase;

    public BatchController(UploadBatchUseCase uploadUseCase, QueryBatchUseCase queryUseCase) {
        this.uploadUseCase = uploadUseCase;
        this.queryUseCase = queryUseCase;
    }

    @Operation(summary = "Upload a settlement file (CSV or JSON) and start asynchronous processing",
            description = """
                    Registers the file as a batch and returns immediately; the reconciliation runs in the background.
                    CSV needs the header `external_ref,amount,currency,settlement_timestamp`; JSON must be an array of
                    objects with the same fields. Timestamps are ISO-8601 with offset (`2026-03-10T15:00:00Z`).
                    The operation is idempotent per (sourceName, file content): re-uploading the same file returns the
                    existing batch (200) instead of creating a new one; if that batch FAILED it is re-queued (202).
                    Optionally declare the settlement period the file covers (periodStart and periodEnd together,
                    ISO-8601); otherwise it is inferred from the settlement timestamps. The period decides which ledger
                    movements are due, i.e. which absences are reported as MISSING_EXTERNAL. Re-uploading the same file
                    with a different declared period is refused (409).""")
    @ApiResponse(responseCode = "202", description = "Batch registered and queued (or re-queued after a failure)")
    @ApiResponse(responseCode = "200", description = "The same file was already registered; existing batch returned")
    @ApiResponse(responseCode = "400", description = "Unsupported/empty file or invalid sourceName", content = @Content)
    @ApiResponse(responseCode = "409", description = "Same file already registered with another settlement period",
            content = @Content)
    @ApiResponse(responseCode = "413", description = "File larger than the configured limit", content = @Content)
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> upload(
            @Parameter(description = "Settlement file (.csv or .json)") @RequestParam("file") MultipartFile file,
            @Parameter(description = "Gateway or clearing house that produced the file", example = "NIUBIZ")
            @RequestParam("sourceName") @NotBlank @Size(max = 100) String sourceName,
            @Parameter(description = "Start of the settlement period covered by the file (ISO-8601, with offset or Z)",
                    example = "2026-03-10T00:00:00Z")
            @RequestParam(name = "periodStart", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant periodStart,
            @Parameter(description = "End of the settlement period (ISO-8601); must be supplied with periodStart",
                    example = "2026-03-10T23:59:59Z")
            @RequestParam(name = "periodEnd", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant periodEnd) throws IOException {

        UploadResult result;
        try (InputStream content = file.getInputStream()) {
            result = uploadUseCase.upload(new UploadBatchCommand(sourceName, file.getOriginalFilename(), content,
                    periodStart, periodEnd));
        }
        long id = result.batch().batchId();
        String statusUrl = "/api/v1/batches/" + id + "/status";
        var body = new UploadResponse(id, result.batch().status().name(), result.outcome().name(), statusUrl);
        HttpStatus http = result.startedProcessing() ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(http).location(URI.create(statusUrl)).body(body);
    }

    @Operation(summary = "Status and progress of a batch")
    @ApiResponse(responseCode = "200", description = "Current status")
    @ApiResponse(responseCode = "404", description = "Unknown batch", content = @Content)
    @GetMapping("/{batchId}/status")
    public BatchStatusResponse status(@PathVariable("batchId") @Positive long batchId) {
        return BatchStatusResponse.from(queryUseCase.getStatus(batchId));
    }

    @Operation(summary = "Executive summary and metrics of a completed batch")
    @ApiResponse(responseCode = "200", description = "Summary")
    @ApiResponse(responseCode = "404", description = "Unknown batch", content = @Content)
    @ApiResponse(responseCode = "409", description = "The batch is not COMPLETED yet (or it FAILED)", content = @Content)
    @GetMapping("/{batchId}/summary")
    public BatchSummaryResponse summary(@PathVariable("batchId") @Positive long batchId) {
        return BatchSummaryResponse.from(queryUseCase.getSummary(batchId));
    }

    @Operation(summary = "Paginated, filterable list of the discrepancies of a completed batch")
    @ApiResponse(responseCode = "200", description = "Page of discrepancies, ordered by result id")
    @ApiResponse(responseCode = "400", description = "Invalid filter or paging", content = @Content)
    @ApiResponse(responseCode = "404", description = "Unknown batch", content = @Content)
    @ApiResponse(responseCode = "409", description = "The batch is not COMPLETED yet (or it FAILED)", content = @Content)
    @GetMapping("/{batchId}/discrepancies")
    public PagedResponse<DiscrepancyResponse> discrepancies(
            @PathVariable("batchId") @Positive long batchId,
            @Parameter(description = "MISSING_INTERNAL, MISSING_EXTERNAL or AMOUNT_MISMATCH")
            @RequestParam(name = "matchStatus", required = false) MatchStatus matchStatus,
            @Parameter(description = "Case-insensitive part of the reference code")
            @RequestParam(name = "reference", required = false) @Size(max = 64) String reference,
            @Parameter(description = "ISO-4217 currency of the discrepancy amount", example = "PEN")
            @RequestParam(name = "currency", required = false) @Size(max = 3) String currency,
            @Parameter(description = "true = only discrepancies closed by a later batch; false = only the open ones; "
                    + "omitted = both")
            @RequestParam(name = "resolved", required = false) Boolean resolved,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "50") @Min(1) @Max(PageQuery.MAX_SIZE) int size) {

        var filter = new DiscrepancyFilter(matchStatus, reference, currency, resolved);
        return PagedResponse.from(queryUseCase.getDiscrepancies(batchId, filter, new PageQuery(page, size)),
                DiscrepancyResponse::from);
    }
}
