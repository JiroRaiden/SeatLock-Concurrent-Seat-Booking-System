package com.seatlock.domain;

/**
 * The persistent state of one seat for one event.
 *
 * <p><b>Read the missing value.</b> There is no {@code HELD} here, and that
 * absence is the central design decision of this whole project.
 *
 * <p>A hold is temporary, expires on its own, and happens on every single seat
 * click. If holds were a database status:
 * <ul>
 *   <li>every seat click would be a write transaction, and a popular drop would
 *       hammer Postgres with UPDATEs that are almost all going to be undone;</li>
 *   <li>every abandoned checkout would leave a stuck row that some cleanup job
 *       has to notice and reverse - and if that job is down, seats stay dead;</li>
 *   <li>the row would be locked or contended for the entire time a human spends
 *       typing card details, which is seconds to minutes, not milliseconds.</li>
 * </ul>
 *
 * <p>So holds live in Redis with a TTL, where expiry is free and automatic, and
 * Postgres only ever records outcomes that are meant to be permanent.
 *
 * @see com.seatlock.hold.SeatHoldService
 */
public enum SeatStatus {

    /** Sellable right now (though someone may be holding it in Redis this second). */
    AVAILABLE,

    /** Sold. Reached only through a confirmed booking, and only inside a transaction. */
    BOOKED,

    /**
     * Withdrawn from sale by the venue - a broken seat, a camera position, house
     * seats. Distinct from BOOKED because no booking exists and because the UI
     * renders it differently: sold seats teach the buyer the show is popular,
     * blocked seats would just look like noise.
     */
    BLOCKED
}
