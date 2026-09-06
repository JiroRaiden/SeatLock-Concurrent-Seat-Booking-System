package com.seatlock.exception;

import java.util.Map;

/**
 * The one exception type the web layer knows how to turn into a response.
 *
 * <p>Services throw this; {@code GlobalExceptionHandler} renders it. Anything
 * else that escapes a controller is, by definition, unexpected, and is logged in
 * full and reported to the client as a bare 500 with no detail. That split is
 * the whole design: <b>errors we anticipated are described precisely, errors we
 * did not are described not at all.</b> Leaking the message of an unanticipated
 * exception is how stack traces, SQL fragments, and file paths end up in a
 * browser's network tab.
 *
 * <p>Note it extends {@link RuntimeException}. Checked exceptions here would
 * force {@code throws} declarations up through every layer and tempt people into
 * empty catch blocks. Spring's transaction manager also only rolls back
 * automatically on unchecked exceptions - a checked one would let a half-finished
 * booking commit.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    /**
     * Extra machine-readable context, surfaced as {@code details} in the JSON.
     * Used for things the client genuinely needs to act on - most importantly
     * {@code unavailableSeatIds}, which lets the UI highlight exactly the seats
     * that were lost instead of saying "something went wrong, try again".
     */
    private final Map<String, Object> details;

    public ApiException(ErrorCode code) {
        this(code, code.defaultMessage(), Map.of(), null);
    }

    public ApiException(ErrorCode code, String message) {
        this(code, message, Map.of(), null);
    }

    public ApiException(ErrorCode code, String message, Map<String, Object> details) {
        this(code, message, details, null);
    }

    public ApiException(ErrorCode code, String message, Map<String, Object> details, Throwable cause) {
        // Chaining the cause keeps the original stack trace in our logs even
        // though it never reaches the client.
        super(message, cause);
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public ErrorCode code() { return code; }
    public Map<String, Object> details() { return details; }

    // ---- Convenience factories, so call sites read as prose --------------

    public static ApiException notFound(String what) {
        // The message never echoes the id that was looked up. "Booking 918 not
        // found" confirms the id space; "We could not find that booking" does not.
        return new ApiException(ErrorCode.NOT_FOUND, "We could not find that " + what + ".");
    }

    public static ApiException seatsUnavailable(java.util.List<Long> seatIds) {
        String message = seatIds.size() == 1
                ? "That seat was taken while you were choosing."
                : seatIds.size() + " of the seats you selected were taken while you were choosing.";
        return new ApiException(ErrorCode.SEAT_UNAVAILABLE, message,
                Map.of("unavailableSeatIds", seatIds));
    }

    public static ApiException holdExpired() {
        return new ApiException(ErrorCode.HOLD_EXPIRED, ErrorCode.HOLD_EXPIRED.defaultMessage());
    }
}
