package com.seatlock.web;

import com.seatlock.exception.ApiException;
import com.seatlock.exception.ErrorCode;
import com.seatlock.web.dto.ApiErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns every exception into the one error envelope from {@code docs/API.md}.
 *
 * <h2>The rule this class enforces</h2>
 *
 * <b>Anticipated failures are described precisely. Unanticipated ones are not
 * described at all.</b>
 *
 * <p>An {@link ApiException} was thrown on purpose by code that knew what it
 * meant, so its message is safe and specific. Anything else - a
 * {@code NullPointerException}, a driver error, a failed cast - is a bug, and
 * its message is written for a developer, not a user. Those messages routinely
 * contain table names, column names, file paths, library versions, and
 * occasionally values from other users' rows. So the catch-all handler logs the
 * whole thing with a trace id and returns nothing but that id.
 *
 * <p>This is not paranoia. "Show the exception message on error" is one of the
 * most common information-disclosure findings in a web application pentest,
 * because it hands an attacker a free map of your internals.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * A short correlation id, generated per error. The user sees it, the log
     * line carries it, and support can join the two. Sixteen hex characters is
     * ample: we only need uniqueness within a log retention window, not global
     * uniqueness forever.
     */
    private static String newTraceId() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(16);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private ResponseEntity<ApiErrorResponse> build(ErrorCode code,
                                                   String message,
                                                   Map<String, String> fieldErrors,
                                                   Map<String, Object> details,
                                                   String traceId) {
        ApiErrorResponse body = new ApiErrorResponse(
                code.name(),
                message,
                code.status().value(),
                Instant.now(),
                traceId,
                fieldErrors == null ? Map.of() : fieldErrors,
                details == null ? Map.of() : details);
        return ResponseEntity.status(code.status()).body(body);
    }

    // ------------------------------------------------------------------
    // Expected, deliberate failures
    // ------------------------------------------------------------------

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApi(ApiException ex, HttpServletRequest request) {
        String traceId = newTraceId();

        // Log level follows the status class, not the fact that an exception
        // occurred. A user losing a contested seat is a 409 and completely
        // normal; logging it at WARN would bury real problems under noise during
        // exactly the traffic spike where you need the logs to be readable.
        if (ex.code().status().is5xxServerError()) {
            log.error("[{}] {} on {} {}", traceId, ex.code(), request.getMethod(), request.getRequestURI(), ex);
        } else {
            log.debug("[{}] {} on {} {}: {}", traceId, ex.code(),
                    request.getMethod(), request.getRequestURI(), ex.getMessage());
        }

        return build(ex.code(), ex.getMessage(), Map.of(), ex.details(), traceId);
    }

    // ------------------------------------------------------------------
    // Request validation
    // ------------------------------------------------------------------

    /** Thrown when a {@code @Valid @RequestBody} fails bean validation. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleBodyValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            // getDefaultMessage() is OUR message from the annotation, not a
            // framework internal, so it is safe to return. merge(..., (a,b) -> a)
            // keeps the first message when a field has several violations -
            // showing a user four complaints about one input is worse than one.
            fieldErrors.merge(error.getField(),
                    error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage(),
                    (first, second) -> first);
        }
        return build(ErrorCode.VALIDATION_FAILED,
                ErrorCode.VALIDATION_FAILED.defaultMessage(), fieldErrors, Map.of(), newTraceId());
    }

    /** Thrown by {@code @Validated} on method parameters (query params, path vars). */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleParamValidation(ConstraintViolationException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getConstraintViolations().forEach(v -> {
            String path = v.getPropertyPath().toString();
            // Property paths look like "search.size"; only the last segment is
            // meaningful to a client.
            String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
            fieldErrors.put(field, v.getMessage());
        });
        return build(ErrorCode.VALIDATION_FAILED,
                ErrorCode.VALIDATION_FAILED.defaultMessage(), fieldErrors, Map.of(), newTraceId());
    }

    /**
     * Unparseable JSON, a wrong type, or an unknown field.
     *
     * <p>We configured Jackson with {@code fail-on-unknown-properties: true}, so
     * a client that sends an extra field gets a 400 instead of having it
     * silently dropped. That is the safer default: silently ignoring
     * {@code "role": "ROLE_ADMIN"} is fine today and a privilege-escalation bug
     * the day somebody adds a matching field to the DTO.
     *
     * <p>The exception's own message is not returned - it quotes the offending
     * JSON and the target Java class, both of which are internal detail.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        String traceId = newTraceId();
        log.debug("[{}] Malformed request body", traceId, ex);
        return build(ErrorCode.MALFORMED_REQUEST,
                ErrorCode.MALFORMED_REQUEST.defaultMessage(), Map.of(), Map.of(), traceId);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        // e.g. /events/abc when the path variable is a Long. Naming the parameter
        // is safe (it is in the public URL template already); echoing the value
        // is not, so we do not.
        return build(ErrorCode.MALFORMED_REQUEST,
                "The value supplied for '" + ex.getName() + "' is not valid.",
                Map.of(), Map.of(), newTraceId());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
        return build(ErrorCode.MALFORMED_REQUEST,
                "Required header '" + ex.getHeaderName() + "' is missing.",
                Map.of(), Map.of(), newTraceId());
    }

    // ------------------------------------------------------------------
    // Security
    // ------------------------------------------------------------------

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthentication(AuthenticationException ex) {
        // Never surface ex.getMessage(): Spring's own messages distinguish
        // "User not found" from "Bad credentials", which is exactly the account
        // enumeration oracle we are trying not to hand out.
        return build(ErrorCode.UNAUTHENTICATED,
                ErrorCode.UNAUTHENTICATED.defaultMessage(), Map.of(), Map.of(), newTraceId());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return build(ErrorCode.FORBIDDEN,
                ErrorCode.FORBIDDEN.defaultMessage(), Map.of(), Map.of(), newTraceId());
    }

    // ------------------------------------------------------------------
    // Concurrency and persistence
    // ------------------------------------------------------------------

    /**
     * The optimistic lock lost.
     *
     * <p>This is the {@code @Version} check firing: two transactions read the
     * same seat and one of them got there first. It is a normal, expected
     * outcome under contention - so it becomes a 409 the client can retry, not
     * a 500 that pages somebody.
     *
     * <p>Reaching here at all means the Redis hold did not filter the conflict,
     * which is rare enough to be worth an INFO line: a sudden burst of these is
     * a real signal that Redis is unhealthy.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleOptimisticLock(OptimisticLockingFailureException ex) {
        String traceId = newTraceId();
        log.info("[{}] Optimistic lock conflict - the database caught a race the hold layer let through", traceId);
        return build(ErrorCode.CONCURRENT_MODIFICATION,
                ErrorCode.CONCURRENT_MODIFICATION.defaultMessage(), Map.of(), Map.of(), traceId);
    }

    /**
     * A database constraint rejected the write.
     *
     * <p>In this system the overwhelmingly likely cause is the partial unique
     * index {@code booking_seats_one_active_per_seat} - the last-line oversell
     * guarantee doing its job. So we translate it to the same 409 a normal seat
     * conflict produces: from the user's point of view the seat was taken, and
     * that is the whole truth they need.
     *
     * <p>{@code ex.getMostSpecificCause().getMessage()} is logged but never
     * returned. Postgres constraint messages quote the conflicting values, which
     * can be another user's data.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleIntegrity(DataIntegrityViolationException ex) {
        String traceId = newTraceId();
        log.warn("[{}] Data integrity violation: {}", traceId, ex.getMostSpecificCause().getMessage());
        return build(ErrorCode.SEAT_UNAVAILABLE,
                "Some of those seats are no longer available.", Map.of(), Map.of(), traceId);
    }

    // ------------------------------------------------------------------
    // Fallbacks
    // ------------------------------------------------------------------

    /** An unmapped URL. Handled explicitly so it produces our envelope, not Spring's. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResource(NoResourceFoundException ex) {
        return build(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.defaultMessage(),
                Map.of(), Map.of(), newTraceId());
    }

    /**
     * The catch-all. Everything that reaches here is a bug.
     *
     * <p>Full detail to the log, nothing but a trace id to the client.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        String traceId = newTraceId();
        log.error("[{}] Unhandled exception on {} {}", traceId,
                request.getMethod(), request.getRequestURI(), ex);
        return build(ErrorCode.INTERNAL_ERROR,
                ErrorCode.INTERNAL_ERROR.defaultMessage() + " Reference: " + traceId,
                Map.of(), Map.of(), traceId);
    }

    /** Exposed so the rate-limit filter can render the same envelope. */
    public static ApiErrorResponse rateLimited(long retryAfterSeconds) {
        return new ApiErrorResponse(
                ErrorCode.TOO_MANY_REQUESTS.name(),
                ErrorCode.TOO_MANY_REQUESTS.defaultMessage(),
                HttpStatus.TOO_MANY_REQUESTS.value(),
                Instant.now(),
                newTraceId(),
                Map.of(),
                Map.of("retryAfterSeconds", retryAfterSeconds));
    }
}
