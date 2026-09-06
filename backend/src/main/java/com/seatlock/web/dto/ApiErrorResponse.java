package com.seatlock.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * The single error envelope every non-2xx response uses.
 *
 * <p>One shape for every error means the frontend writes one parser and one
 * error component. APIs that return {@code {"error": "..."}} from one handler
 * and {@code {"message": "..."}} from another force every client to guess.
 *
 * @param code        stable machine-readable identifier; clients branch on this
 * @param message     safe to show a user, always
 * @param status      HTTP status, repeated in the body so a logged payload is
 *                    self-describing without its response headers
 * @param timestamp   when we generated it
 * @param traceId     correlates this response with our server logs. A user can
 *                    quote it to support; support can find the exact request.
 *                    This is what lets us give the user nothing useful about the
 *                    failure while still being able to debug it.
 * @param fieldErrors per-field validation messages, keyed by field name
 * @param details     endpoint-specific structured context, e.g. which seat ids
 *                    were lost
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiErrorResponse(
        String code,
        String message,
        int status,
        Instant timestamp,
        String traceId,
        Map<String, String> fieldErrors,
        Map<String, Object> details
) {
    public static ApiErrorResponse of(String code, String message, int status, String traceId) {
        return new ApiErrorResponse(code, message, status, Instant.now(), traceId, Map.of(), Map.of());
    }
}
