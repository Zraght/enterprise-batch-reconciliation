package com.joseantonio.reconciliation.infrastructure.web;

import com.joseantonio.reconciliation.domain.exception.BatchConflictException;
import com.joseantonio.reconciliation.domain.exception.BatchNotCompletedException;
import com.joseantonio.reconciliation.domain.exception.BatchNotFoundException;
import com.joseantonio.reconciliation.domain.exception.DomainException;
import com.joseantonio.reconciliation.domain.exception.FileProcessingException;
import com.joseantonio.reconciliation.domain.exception.InvalidRequestException;
import com.joseantonio.reconciliation.domain.exception.InvalidTransactionException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One place that turns every failure into an RFC 7807 {@link ProblemDetail}. Internal details (SQL, stack traces,
 * file-system paths) are logged, never returned.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BatchNotFoundException.class)
    public ResponseEntity<ProblemDetail> notFound(BatchNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Batch not found", e.code(), e.getMessage(), null);
    }

    @ExceptionHandler(BatchNotCompletedException.class)
    public ResponseEntity<ProblemDetail> notCompleted(BatchNotCompletedException e) {
        return problem(HttpStatus.CONFLICT, "Batch not completed", e.code(), e.getMessage(),
                Map.of("batchStatus", e.status().name()));
    }

    @ExceptionHandler(BatchConflictException.class)
    public ResponseEntity<ProblemDetail> conflict(BatchConflictException e) {
        return problem(HttpStatus.CONFLICT, "Conflict with an existing batch", e.code(), e.getMessage(), null);
    }

    @ExceptionHandler({InvalidRequestException.class, InvalidTransactionException.class})
    public ResponseEntity<ProblemDetail> badRequest(DomainException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", e.code(), e.getMessage(), null);
    }

    @ExceptionHandler(FileProcessingException.class)
    public ResponseEntity<ProblemDetail> unprocessableFile(FileProcessingException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "File cannot be processed", e.code(), e.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> bodyValidation(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> errors.putIfAbsent(f.getField(), f.getDefaultMessage()));
        return problem(HttpStatus.BAD_REQUEST, "Validation failed", "VALIDATION_FAILED",
                "The request body has invalid fields", Map.of("errors", errors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> parameterValidation(ConstraintViolationException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        e.getConstraintViolations().forEach(v -> errors.putIfAbsent(v.getPropertyPath().toString(), v.getMessage()));
        return problem(HttpStatus.BAD_REQUEST, "Validation failed", "VALIDATION_FAILED",
                "One or more request parameters are invalid", Map.of("errors", errors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> unreadableBody(HttpMessageNotReadableException e) {
        return problem(HttpStatus.BAD_REQUEST, "Malformed request body", "MALFORMED_BODY",
                "The request body is not valid JSON or contains unknown or mistyped fields", null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> typeMismatch(MethodArgumentTypeMismatchException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid parameter", "INVALID_PARAMETER",
                "Parameter '" + e.getName() + "' has an invalid value", null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> tooLarge(MaxUploadSizeExceededException e) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "File too large", "FILE_TOO_LARGE",
                "The uploaded file exceeds the configured maximum size", null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> integrity(DataIntegrityViolationException e) {
        log.warn("Data integrity violation", e);
        return problem(HttpStatus.CONFLICT, "Conflict", "DATA_CONFLICT",
                "The operation conflicts with existing data (for example, references that already exist)", null);
    }

    /** Safety net. Spring MVC's own exceptions (405, 415, missing parameter...) already carry their ProblemDetail. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> fallback(Exception e) {
        if (e instanceof ErrorResponse er) {
            return ResponseEntity.status(er.getStatusCode()).headers(er.getHeaders()).body(er.getBody());
        }
        log.error("Unhandled exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "INTERNAL_ERROR",
                "An unexpected error occurred. It has been logged.", null);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String code, String detail,
                                                         Map<String, ?> extra) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        pd.setProperty("code", code);
        if (extra != null) {
            extra.forEach(pd::setProperty);
        }
        return ResponseEntity.status(status).body(pd);
    }
}
