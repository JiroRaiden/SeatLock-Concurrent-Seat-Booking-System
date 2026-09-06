package com.seatlock.hold;

import java.util.List;

/**
 * The outcome of an attempt to hold seats.
 *
 * <p>A record rather than a thrown exception, because losing a contested seat is
 * an <em>expected</em> outcome of this system, not an exceptional one. Under a
 * hot ticket drop most hold attempts fail; that is the system working. Exceptions
 * are for the unexpected, they are expensive to construct (stack trace capture),
 * and using them for ordinary control flow hides the branch from the reader.
 *
 * <p>The caller must look at {@link #acquired()} before using either list.
 * Making that impossible to forget is why the two lists are never both
 * populated.
 *
 * @param acquired            true if every requested seat is now held
 * @param heldSeatIds         the seats now held, in canonical order; empty on failure
 * @param conflictingSeatIds  the seats somebody else was holding; empty on success
 */
public record HoldResult(
        boolean acquired,
        List<Long> heldSeatIds,
        List<Long> conflictingSeatIds
) {

    /** Compact constructor: defensive copies so a caller cannot mutate the result. */
    public HoldResult {
        heldSeatIds = List.copyOf(heldSeatIds);
        conflictingSeatIds = List.copyOf(conflictingSeatIds);
    }

    public static HoldResult acquired(List<Long> seatIds) {
        return new HoldResult(true, seatIds, List.of());
    }

    public static HoldResult rejected(List<Long> conflictingSeatIds) {
        return new HoldResult(false, List.of(), conflictingSeatIds);
    }
}
