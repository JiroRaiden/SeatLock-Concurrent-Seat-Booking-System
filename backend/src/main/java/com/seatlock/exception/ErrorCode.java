package com.seatlock.exception;

import org.springframework.http.HttpStatus;

/**
 * Every error this API can return, with its HTTP status attached.
 *
 * <p>Clients branch on {@code code}, not on the human-readable message. Messages
 * get reworded, translated, and made friendlier; a stable machine-readable code
 * means doing that never breaks a client. This enum is therefore part of the
 * public API contract - see {@code docs/API.md}.
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST,
            "Some of the values you sent are not valid."),

    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST,
            "The request could not be understood."),

    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED,
            "You need to sign in to do that."),

    /**
     * Deliberately does not say whether it was the email or the password.
     * "No account with that email" is a free account-enumeration oracle: an
     * attacker feeds in a leaked address list and learns exactly who has an
     * account here, which is the first step of a credential-stuffing campaign.
     */
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED,
            "Email or password is incorrect."),

    TOKEN_REUSE_DETECTED(HttpStatus.UNAUTHORIZED,
            "Your session has been ended for security reasons. Please sign in again."),

    FORBIDDEN(HttpStatus.FORBIDDEN,
            "You are not allowed to do that."),

    NOT_FOUND(HttpStatus.NOT_FOUND,
            "We could not find that."),

    SALES_CLOSED(HttpStatus.CONFLICT,
            "Booking has closed for this show."),

    SEAT_UNAVAILABLE(HttpStatus.CONFLICT,
            "Some of those seats are no longer available."),

    HOLD_EXPIRED(HttpStatus.CONFLICT,
            "Your seats were released because the reservation timer ran out."),

    BOOKING_NOT_PENDING(HttpStatus.CONFLICT,
            "This booking is not in a state where that is possible."),

    /**
     * The optimistic lock lost a race. 409 rather than 500 because nothing is
     * broken - the client can simply try again and will usually succeed.
     */
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT,
            "Someone else changed that at the same moment. Please try again."),

    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY,
            "That idempotency key was already used for a different request."),

    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS,
            "Too many requests. Please slow down."),

    PAYMENT_DECLINED(HttpStatus.PAYMENT_REQUIRED,
            "The payment was declined."),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR,
            "Something went wrong on our side.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() { return status; }

    /**
     * A message that is always safe to display to an end user: no internal ids,
     * no SQL, no class names, no hints about what exists on the server.
     */
    public String defaultMessage() { return defaultMessage; }
}
