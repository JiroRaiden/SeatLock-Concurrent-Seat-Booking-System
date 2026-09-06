package com.seatlock.domain;

/**
 * Publication state.
 *
 * <p>DRAFT events are invisible to the public listing <em>and</em> reject holds.
 * Both checks matter: hiding a row from a list is presentation, refusing to sell
 * it is enforcement.
 */
public enum EventStatus {
    DRAFT,
    PUBLISHED,
    CANCELLED
}
