package com.seatlock.domain;

/**
 * The lifecycle of a booking.
 *
 * <pre>
 *                  confirm (payment ok)
 *   PENDING  ─────────────────────────────►  CONFIRMED
 *      │                                          │
 *      │ user cancels / hold TTL elapses          │ user cancels before showtime
 *      ▼                                          ▼
 *   EXPIRED                                   CANCELLED
 * </pre>
 *
 * <p>PENDING and CONFIRMED are the two states that hold a seat. EXPIRED and
 * CANCELLED both release it - they are kept separate because the reasons differ
 * and support staff will absolutely ask which one happened.
 */
public enum BookingStatus {

    /** Seats are reserved in Redis; payment has not completed. Has an expiry. */
    PENDING,

    /** Paid. Seats are BOOKED in Postgres. The Redis hold has been handed back. */
    CONFIRMED,

    /** Explicitly cancelled by the user or an admin. Seats returned to sale. */
    CANCELLED,

    /** The hold ran out before payment completed. Seats returned to sale. */
    EXPIRED;

    /** True while this booking still has a claim on its seats. */
    public boolean holdsSeats() {
        return this == PENDING || this == CONFIRMED;
    }

    public boolean isTerminal() {
        return this == CANCELLED || this == EXPIRED || this == CONFIRMED;
    }
}
