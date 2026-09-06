package com.seatlock.booking;

import com.seatlock.domain.*;
import com.seatlock.exception.ApiException;
import com.seatlock.exception.ErrorCode;
import com.seatlock.repository.BookingRepository;
import com.seatlock.repository.EventSeatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The database units of work for booking, each in its own transaction.
 *
 * <h2>Why this is a separate class from {@code BookingService}</h2>
 *
 * Two reasons, and the first one is a genuine Spring trap worth knowing.
 *
 * <p><b>1. {@code @Transactional} does not work on self-invocation.</b> Spring
 * implements it with a proxy: something wraps your bean and opens a transaction
 * as the call passes through the wrapper. A call from one method of a class to
 * another method of the <em>same</em> class never leaves the object, so it never
 * passes through the proxy, so the annotation does nothing at all - silently.
 * The code looks transactional, the logs show no transaction, and the bug only
 * surfaces when a partial failure fails to roll back.
 *
 * <p>Splitting the transactional work into its own bean means every call is a
 * real call to a real proxy.
 *
 * <p><b>2. It forces the transaction boundary to be a decision.</b>
 * {@code BookingService} orchestrates: it talks to Redis, calls the payment
 * gateway, and calls in here for the parts that must be atomic in Postgres.
 * Keeping those separate makes it obvious that the Redis call and the HTTP call
 * to the payment provider are <em>outside</em> the transaction - which is
 * exactly where they belong. A database transaction that stays open across a
 * network call to a third party holds a connection from a pool of twenty for as
 * long as that third party feels like taking, and that is how one slow
 * dependency takes down a whole service.
 */
@Service
public class BookingPersistence {

    private static final Logger log = LoggerFactory.getLogger(BookingPersistence.class);

    private final BookingRepository bookings;
    private final EventSeatRepository eventSeats;

    public BookingPersistence(BookingRepository bookings, EventSeatRepository eventSeats) {
        this.bookings = bookings;
        this.eventSeats = eventSeats;
    }

    /**
     * Write the PENDING booking that backs a freshly-acquired Redis hold.
     *
     * <p>Runs after the Redis hold succeeded. If anything in here fails, the
     * caller releases that hold - see {@code BookingService.createHold}.
     *
     * <p>Note the seats are <b>not</b> marked BOOKED here. A pending booking has
     * not been paid for; the seat's persistent status only changes on
     * confirmation. What does claim the seat in the database is the
     * {@code booking_seats} row itself, through the partial unique index.
     */
    @Transactional
    public Booking createPendingBooking(User user,
                                        Event event,
                                        List<EventSeat> seats,
                                        UUID holdId,
                                        Instant expiresAt) {

        List<Long> seatIds = seats.stream().map(EventSeat::getId).toList();

        // Clear any lapsed PENDING booking still claiming these seats, so its
        // stale booking_seats rows do not trip the unique index below. See the
        // long note on this repository method - it closes a real window where a
        // seat is free in Redis but still claimed in Postgres.
        int swept = bookings.expireStalePendingClaiming(seatIds, Instant.now());
        if (swept > 0) {
            log.debug("Expired {} stale pending booking(s) that were still claiming these seats", swept);
        }

        // Re-read status inside the transaction. The caller checked availability
        // before going to Redis, but that read was in a different transaction and
        // is now several milliseconds old. This is the check that counts.
        List<EventSeat> unavailable = seats.stream().filter(s -> !s.isAvailable()).toList();
        if (!unavailable.isEmpty()) {
            throw ApiException.seatsUnavailable(unavailable.stream().map(EventSeat::getId).toList());
        }

        Booking booking = new Booking(user, event, expiresAt, holdId);
        for (EventSeat seat : seats) {
            booking.addSeat(seat, seat.getPriceTier().getPriceMinor());
        }

        // save() cascades to the booking_seats rows. If two requests somehow
        // reach here for the same seat, the partial unique index rejects the
        // second with a DataIntegrityViolationException, which the global
        // handler renders as a 409. That is the database having the last word.
        return bookings.save(booking);
    }

    /**
     * PENDING -> CONFIRMED, and the seats to BOOKED.
     *
     * <p>This is the transaction the optimistic lock protects. Every
     * {@code EventSeat} loaded here carries its {@code version}; the UPDATE
     * Hibernate emits on flush includes {@code AND version = ?}. If a concurrent
     * transaction booked the same seat between our read and our write, our
     * UPDATE matches zero rows and Hibernate raises
     * {@code OptimisticLockException} - which becomes a 409 the client can
     * retry, not a double sale.
     *
     * @throws ApiException if the booking is no longer pending, has expired, or
     *                      a seat is no longer available
     */
    @Transactional
    public Booking confirmBooking(UUID publicId, Long userId, Instant now) {
        Booking booking = bookings.findOwned(publicId, userId)
                .orElseThrow(() -> ApiException.notFound("booking"));

        if (booking.getStatus() != BookingStatus.PENDING) {
            throw new ApiException(ErrorCode.BOOKING_NOT_PENDING,
                    "This booking has already been " + booking.getStatus().name().toLowerCase() + ".");
        }
        if (booking.isExpired(now)) {
            throw ApiException.holdExpired();
        }

        // One query for all the seats, with their tiers, instead of walking the
        // lazy association and firing a query per seat.
        List<Long> seatIds = booking.getSeats().stream()
                .map(bs -> bs.getEventSeat().getId())
                .toList();
        List<EventSeat> seats = eventSeats.findForEvent(booking.getEvent().getId(), seatIds);

        List<Long> notAvailable = seats.stream()
                .filter(s -> !s.isAvailable())
                .map(EventSeat::getId)
                .toList();
        if (!notAvailable.isEmpty()) {
            // Reachable if this booking's own hold lapsed, the reaper expired it,
            // and somebody else bought the seat in the meantime.
            throw ApiException.seatsUnavailable(notAvailable);
        }

        for (EventSeat seat : seats) {
            seat.markBooked();     // guarded on the entity; version bumps on flush
        }
        booking.confirm(now);

        // No explicit save() call. `booking` and every `seat` are managed
        // entities inside this transaction, so Hibernate's dirty checking writes
        // the changes at flush time. Calling save() would work but would imply
        // that not calling it leaves them unsaved, which is not how JPA works
        // and is worth being precise about.
        return booking;
    }

    /**
     * Cancel a booking and give the seats back.
     *
     * <p>The {@code booking_seats.active} flag is not touched here. A database
     * trigger flips it whenever a booking moves to CANCELLED or EXPIRED, which
     * is what actually releases the seat from the partial unique index. Putting
     * that invariant in the database rather than here means no future code path
     * - including a manual UPDATE - can leave a cancelled booking holding a live
     * claim.
     */
    @Transactional
    public Booking cancelBooking(UUID publicId, Long userId, Instant now) {
        Booking booking = bookings.findOwned(publicId, userId)
                .orElseThrow(() -> ApiException.notFound("booking"));

        // Cancellable states are PENDING and CONFIRMED. Anything already
        // CANCELLED or EXPIRED is refused with a 409 rather than treated as a
        // silent no-op, so a double-click on Cancel gets an honest answer.
        if (booking.getStatus() != BookingStatus.PENDING
                && booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new ApiException(ErrorCode.BOOKING_NOT_PENDING,
                    "This booking has already been " + booking.getStatus().name().toLowerCase() + ".");
        }

        // Only a CONFIRMED booking has seats in the BOOKED state to hand back;
        // a PENDING one never changed their status in the first place.
        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            List<Long> seatIds = booking.getSeats().stream()
                    .map(bs -> bs.getEventSeat().getId())
                    .toList();
            eventSeats.findForEvent(booking.getEvent().getId(), seatIds)
                      .forEach(EventSeat::release);
        }

        booking.cancel(now);
        return booking;
    }

    /**
     * Mark a lapsed PENDING booking as EXPIRED. Used by the background reaper.
     *
     * <p>{@code REQUIRES_NEW} so that each booking is its own transaction: one
     * booking that fails to expire (an optimistic lock conflict with a user
     * confirming at that exact moment) must not roll back the other forty-nine
     * in the batch.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expireBooking(Long bookingId, Instant now) {
        bookings.findById(bookingId).ifPresent(booking -> {
            // Re-check under this transaction. The reaper's query ran a moment
            // ago and the user may have confirmed since.
            if (booking.getStatus() == BookingStatus.PENDING && booking.isExpired(now)) {
                booking.expire(now);
                log.debug("Expired booking {}", booking.getReference());
            }
        });
    }

    /**
     * Read a booking the caller owns, with its seats loaded, for rendering.
     *
     * <p>{@code readOnly = true} is not decoration: it lets Hibernate skip
     * setting up dirty-check snapshots for every loaded entity, and it tells
     * Postgres the transaction will not write, which lets it take a cheaper
     * snapshot.
     */
    @Transactional(readOnly = true)
    public Booking loadOwned(UUID publicId, Long userId) {
        Booking booking = bookings.findOwned(publicId, userId)
                .orElseThrow(() -> ApiException.notFound("booking"));
        // Touch the lazy collection while the session is still open, so the
        // mapper outside this method does not hit LazyInitializationException.
        booking.getSeats().forEach(bs -> bs.getEventSeat().getSeat().getRowLabel());
        return booking;
    }
}
