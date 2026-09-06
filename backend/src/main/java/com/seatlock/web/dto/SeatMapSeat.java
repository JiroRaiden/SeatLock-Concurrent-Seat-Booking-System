package com.seatlock.web.dto;

/**
 * One seat as the browser sees it.
 *
 * @param colIndex column position with the centre-aisle gap already applied, so
 *                 the client renders with {@code gridColumn: colIndex} and never
 *                 has to know where the aisle is
 * @param status   AVAILABLE / BOOKED / BLOCKED / HELD.
 *
 *                 HELD does not exist in the database. It is computed per
 *                 request by asking Redis which of this event's seats currently
 *                 have a live hold, then overlaying that onto the persisted
 *                 status. That is why the enum here is a String rather than the
 *                 {@code SeatStatus} enum: the wire type has one more value than
 *                 the storage type, and forcing them to be the same type would
 *                 mean adding HELD to the database enum, which is exactly the
 *                 design mistake this whole project is built to avoid.
 */
public record SeatMapSeat(
        Long id,
        String label,
        Short seatNumber,
        Short colIndex,
        Long tierId,
        Long priceMinor,
        String status
) { }
