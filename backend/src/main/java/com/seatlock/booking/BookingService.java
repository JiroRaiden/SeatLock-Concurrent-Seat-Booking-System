package com.seatlock.booking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatlock.config.HoldProperties;
import com.seatlock.domain.*;
import com.seatlock.exception.ApiException;
import com.seatlock.exception.ErrorCode;
import com.seatlock.hold.HoldResult;
import com.seatlock.hold.SeatHoldService;
import com.seatlock.repository.BookingRepository;
import com.seatlock.repository.EventRepository;
import com.seatlock.repository.EventSeatRepository;
import com.seatlock.repository.UserRepository;
import com.seatlock.security.AuthenticatedUser;
import com.seatlock.web.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Orchestrates the booking flow across Redis, Postgres and the payment gateway.
 *
 * <h2>Read this first: the ordering problem</h2>
 *
 * Three systems have to agree, and there is no transaction that spans them.
 * Redis cannot join a Postgres transaction; the payment provider certainly
 * cannot. So we cannot make the whole thing atomic, and pretending otherwise is
 * how money and seats go missing.
 *
 * <p>What we can do is <b>choose the order of operations so that every possible
 * failure point leaves a state we can recover from</b>. That is the entire
 * design principle of this class.
 *
 * <pre>
 *   HOLD:     1. read seat availability from Postgres      (cheap, may be stale)
 *             2. acquire the hold in Redis                 (the real gate)
 *             3. write the PENDING booking to Postgres     (durable claim)
 *             4. if 3 fails, release 2
 *
 *   CONFIRM:  1. verify we still own the Redis hold
 *             2. AUTHORIZE the payment (reserve, do not take)
 *             3. commit the booking in Postgres            (the point of no return)
 *             4. CAPTURE the payment
 *             5. release the Redis hold (the seat is ours permanently now)
 *             6. if 3 fails, VOID the authorization
 * </pre>
 *
 * <p>Now walk the failure points and check each one is survivable:
 *
 * <ul>
 *   <li><b>Crash between hold-2 and hold-3.</b> A Redis hold exists with no
 *       booking behind it. It expires in 8 minutes and the seat returns to sale.
 *       Cost: one seat, briefly unsellable. Nobody is harmed.</li>
 *   <li><b>Crash between confirm-2 and confirm-3.</b> Money is authorized but
 *       not captured, and no booking exists. Card authorizations expire on their
 *       own (typically 7 days) and no money ever moves. The customer sees a
 *       pending hold that vanishes. This is the whole reason we authorize
 *       rather than charge.</li>
 *   <li><b>Crash between confirm-3 and confirm-4.</b> The booking exists and is
 *       confirmed, but we never captured. <b>We have given away a seat for
 *       free.</b> This is the one genuinely bad outcome, and it is deliberately
 *       placed last and made as small as possible - a real system reconciles it
 *       from an outbox table or a nightly settlement job. It is called out in
 *       docs/04-booking-lifecycle.md rather than swept under the rug, because
 *       "which failure did you choose to accept?" is the question that separates
 *       someone who has thought about this from someone who has not.</li>
 *   <li><b>Crash between confirm-4 and confirm-5.</b> A Redis hold outlives its
 *       booking. It expires by itself; meanwhile the seat is BOOKED in Postgres
 *       anyway, so nobody else could take it regardless.</li>
 * </ul>
 *
 * <p>Every path except one degrades to "a seat is briefly unavailable", which is
 * the correct thing to be bad at. Selling the same seat twice is not.
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final EventRepository events;
    private final EventSeatRepository eventSeats;
    private final BookingRepository bookings;
    private final UserRepository users;
    private final SeatHoldService holds;
    private final BookingPersistence persistence;
    private final IdempotencyService idempotency;
    private final PaymentGateway payments;
    private final BookingMapper mapper;
    private final HoldProperties holdProperties;
    private final ObjectMapper objectMapper;

    public BookingService(EventRepository events,
                          EventSeatRepository eventSeats,
                          BookingRepository bookings,
                          UserRepository users,
                          SeatHoldService holds,
                          BookingPersistence persistence,
                          IdempotencyService idempotency,
                          PaymentGateway payments,
                          BookingMapper mapper,
                          HoldProperties holdProperties,
                          ObjectMapper objectMapper) {
        this.events = events;
        this.eventSeats = eventSeats;
        this.bookings = bookings;
        this.users = users;
        this.holds = holds;
        this.persistence = persistence;
        this.idempotency = idempotency;
        this.payments = payments;
        this.mapper = mapper;
        this.holdProperties = holdProperties;
        this.objectMapper = objectMapper;
    }

    // =====================================================================
    // HOLD
    // =====================================================================

    /**
     * Reserve seats and start the clock.
     *
     * <p>Deliberately <b>not</b> {@code @Transactional}. If it were, the Redis
     * round trip and the availability read would sit inside one long database
     * transaction, holding a pooled connection while we talk to another server.
     * The only part that needs to be atomic is the Postgres write, and that
     * happens inside {@link BookingPersistence#createPendingBooking}, which is a
     * call to a different bean and therefore a real proxy boundary.
     */
    public HoldResponse createHold(AuthenticatedUser caller, Long eventId, List<Long> requestedSeatIds) {

        // ---- 0. Validate the request against business rules ----------------
        List<Long> seatIds = requestedSeatIds.stream().distinct().sorted().toList();

        if (seatIds.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Select at least one seat.");
        }
        if (seatIds.size() > holdProperties.getMaxSeatsPerBooking()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "You can book at most " + holdProperties.getMaxSeatsPerBooking() + " seats at a time.");
        }

        Event event = events.findWithVenue(eventId)
                .orElseThrow(() -> ApiException.notFound("event"));

        Instant now = Instant.now();
        // Server-side check, always. The frontend hides the button once sales
        // close, but that is a courtesy to honest users - anyone can POST here
        // directly. "The UI prevents it" is never an access control.
        if (!event.isOpenForSale(now)) {
            throw new ApiException(ErrorCode.SALES_CLOSED, ErrorCode.SALES_CLOSED.defaultMessage());
        }

        // ---- 1. Read availability -----------------------------------------
        // Scoped by eventId as well as by seat id, so a request against event 7
        // cannot reach a seat belonging to event 8 by guessing its id.
        List<EventSeat> seats = eventSeats.findForEvent(eventId, seatIds);

        if (seats.size() != seatIds.size()) {
            // Some ids do not belong to this event, or do not exist. Reported as
            // "unavailable" rather than "not found" on purpose: the two answers
            // would let a caller map out which seat ids exist.
            Set<Long> found = seats.stream().map(EventSeat::getId).collect(java.util.stream.Collectors.toSet());
            List<Long> missing = seatIds.stream().filter(id -> !found.contains(id)).toList();
            throw ApiException.seatsUnavailable(missing);
        }

        List<Long> alreadyTaken = seats.stream()
                .filter(s -> !s.isAvailable())
                .map(EventSeat::getId)
                .toList();
        if (!alreadyTaken.isEmpty()) {
            // A cheap early exit. This read is already stale by the time we act
            // on it - the authoritative checks are Redis (step 2) and the
            // database (step 3) - but rejecting here saves a Redis round trip
            // for the common case of clicking a seat that was sold an hour ago.
            throw ApiException.seatsUnavailable(alreadyTaken);
        }

        // ---- 2. Acquire the hold in Redis ---------------------------------
        // The token is generated here and becomes the booking's public id.
        // It must exist before the booking row does, because the hold has to be
        // taken before we write anything durable.
        UUID holdId = UUID.randomUUID();
        Duration ttl = holdProperties.getTtl();

        HoldResult result = holds.acquire(eventId, seatIds, holdId, ttl);
        if (!result.acquired()) {
            throw ApiException.seatsUnavailable(result.conflictingSeatIds());
        }

        // ---- 3. Persist the pending booking -------------------------------
        Instant expiresAt = now.plus(ttl);
        Booking booking;
        try {
            User user = users.getReferenceById(caller.id());
            booking = persistence.createPendingBooking(user, event, seats, holdId, expiresAt);

        } catch (RuntimeException ex) {
            // ---- 4. Compensate -------------------------------------------
            // The database refused. Hand the Redis seats straight back rather
            // than leaving them locked for eight minutes for a booking that does
            // not exist. release() is a compare-and-delete on our own token, so
            // it cannot touch anyone else's hold even if the state is confusing.
            holds.release(eventId, seatIds, holdId);
            log.debug("Rolled back Redis hold {} after persistence failure", holdId);
            throw ex;
        }

        log.info("Hold {} created for user {} on event {} ({} seats, expires {})",
                holdId, caller.id(), eventId, seatIds.size(), expiresAt);

        return new HoldResponse(
                holdId,
                eventId,
                expiresAt,
                ttl.toSeconds(),
                booking.getTotalMinor(),
                seats.stream()
                     .sorted(Comparator.comparing((EventSeat s) -> s.getSeat().getRowIndex())
                                       .thenComparing(s -> s.getSeat().getSeatNumber()))
                     .map(s -> new HeldSeatDto(
                             s.getId(),
                             s.getSeat().label(),
                             s.getSeat().getSection(),
                             s.getPriceTier().getPriceMinor()))
                     .toList());
    }

    /**
     * Grant a one-off extension while the user is mid-payment.
     *
     * <p>Extends both stores: Redis, which actually releases the seat, and the
     * booking row, which the UI reads to draw its countdown. If Redis says we no
     * longer own every seat, the whole hold is dead and we say so rather than
     * extending a reservation that cannot be completed.
     */
    @Transactional
    public ExtendHoldResponse extendHold(AuthenticatedUser caller, UUID holdId) {
        Booking booking = bookings.findOwned(holdId, caller.id())
                .orElseThrow(() -> ApiException.notFound("reservation"));

        if (booking.getStatus() != BookingStatus.PENDING) {
            throw new ApiException(ErrorCode.BOOKING_NOT_PENDING,
                    "That reservation is no longer active.");
        }

        List<Long> seatIds = booking.getSeats().stream()
                .map(bs -> bs.getEventSeat().getId())
                .toList();
        Long eventId = booking.getEvent().getId();

        Instant now = Instant.now();

        // How much time is actually left, according to Redis - the authority.
        Duration remaining = holds.timeToLive(eventId, seatIds.get(0))
                .orElseThrow(ApiException::holdExpired);

        // The obvious implementation - "set the TTL to the extension period" -
        // is wrong, and wrong in the direction that hurts users. Extending ten
        // seconds into an eight-minute hold would replace 470 seconds with 180
        // and SHORTEN the reservation. "Extend" must add to what remains.
        Instant proposed = now.plus(remaining).plus(holdProperties.getExtension());

        // A cap measured from when the booking was created, rather than a
        // counter of how many times extend has been called. It enforces
        // "one extension" without needing a column to remember it: a second
        // call computes a target beyond the cap, gets clamped back to the same
        // instant, and gains nothing. Fewer pieces of state, same rule.
        Instant cap = booking.getCreatedAt()
                .plus(holdProperties.getTtl())
                .plus(holdProperties.getExtension());

        Instant expiresAt = proposed.isAfter(cap) ? cap : proposed;
        Duration newTtl = Duration.between(now, expiresAt);

        if (newTtl.isNegative() || newTtl.isZero()) {
            throw ApiException.holdExpired();
        }

        if (!holds.extend(eventId, seatIds, holdId, newTtl)) {
            throw ApiException.holdExpired();
        }

        // Keep the persisted expiry in step so a page refresh shows the right
        // countdown. Redis remains the authority; this is a mirror for display.
        booking.setExpiresAtForExtension(expiresAt);

        return new ExtendHoldResponse(expiresAt, newTtl.toSeconds());
    }

    /**
     * Release a hold early - the user pressed Back.
     *
     * <p>Worth doing rather than letting the TTL run: during a busy drop, eight
     * minutes of a seat nobody wants is eight minutes somebody else could have
     * bought it.
     */
    public void releaseHold(AuthenticatedUser caller, UUID holdId) {
        // loadOwned runs in its own read-only transaction and initialises the
        // seat collection before returning, so the lazy walk below is safe out
        // here. Calling the repository directly would hand back a detached
        // entity whose associations blow up on first touch.
        Booking booking = persistence.loadOwned(holdId, caller.id());

        if (booking.getStatus() != BookingStatus.PENDING) {
            return;   // already gone; releasing is idempotent by design
        }

        Long eventId = booking.getEvent().getId();
        List<Long> seatIds = booking.getSeats().stream()
                .map(bs -> bs.getEventSeat().getId())
                .toList();

        persistence.cancelBooking(holdId, caller.id(), Instant.now());
        holds.release(eventId, seatIds, holdId);
    }

    // =====================================================================
    // CONFIRM
    // =====================================================================

    /**
     * Turn a hold into a paid booking. See the class comment for the ordering.
     *
     * @param idempotencyKey a client-generated key, required. Two requests with
     *                       the same key produce one booking and two identical
     *                       responses.
     */
    public BookingDetail confirm(AuthenticatedUser caller,
                                 UUID holdId,
                                 ConfirmBookingRequest request,
                                 String idempotencyKey) {

        User user = users.getReferenceById(caller.id());
        String endpoint = "POST /api/v1/bookings/" + holdId + "/confirm";

        // ---- Idempotency gate ---------------------------------------------
        IdempotencyService.Claim claim =
                idempotency.claim(user, idempotencyKey, endpoint, canonicalise(request));

        switch (claim.outcome()) {
            case REPLAY -> {
                log.debug("Replaying stored response for idempotency key on booking {}", holdId);
                return readStored(claim.storedBody());
            }
            case MISMATCH -> throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    ErrorCode.IDEMPOTENCY_KEY_REUSED.defaultMessage());
            case IN_FLIGHT -> throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION,
                    "This request is already being processed. Please wait a moment and try again.");
            case PROCEED -> { /* fall through and do the work */ }
        }

        String authorizationId = null;
        // Once the booking's transaction has committed there is nothing left to
        // undo, and the compensating actions below become actively wrong:
        // voiding the authorization would give away a seat for free, and
        // releasing the idempotency key would let a retry try to book seats this
        // customer already owns. Everything after the commit is best-effort
        // bookkeeping, so failures there must not trigger a rollback we cannot
        // actually perform.
        boolean committed = false;
        try {
            Instant now = Instant.now();

            // ---- 1. Is the hold still ours? -------------------------------
            Booking pending = persistence.loadOwned(holdId, caller.id());

            if (pending.getStatus() != BookingStatus.PENDING) {
                throw new ApiException(ErrorCode.BOOKING_NOT_PENDING,
                        "This booking has already been " + pending.getStatus().name().toLowerCase() + ".");
            }
            if (pending.isExpired(now)) {
                throw ApiException.holdExpired();
            }

            List<Long> seatIds = pending.getSeats().stream()
                    .map(bs -> bs.getEventSeat().getId())
                    .toList();
            Long eventId = pending.getEvent().getId();

            if (!holds.ownsAll(eventId, seatIds, holdId)) {
                // Redis says the reservation lapsed. Refuse rather than quietly
                // completing: somebody else may already be holding these seats,
                // and the database write would then fail anyway - but with a far
                // more confusing error, after we had taken the money.
                throw ApiException.holdExpired();
            }

            // ---- 2. Authorize (reserve, do not take) ----------------------
            PaymentGateway.PaymentResult payment = payments.authorize(
                    request.paymentToken(), pending.getTotalMinor(), pending.getReference());

            if (!payment.approved()) {
                throw new ApiException(ErrorCode.PAYMENT_DECLINED,
                        payment.declineReason() == null
                                ? ErrorCode.PAYMENT_DECLINED.defaultMessage()
                                : payment.declineReason());
            }
            authorizationId = payment.authorizationId();

            // ---- 3. Commit the booking (point of no return) ---------------
            Booking confirmed = persistence.confirmBooking(holdId, caller.id(), now);
            committed = true;   // point of no return crossed

            // Re-read in a fresh read-only transaction to build the response.
            // `confirmed` is detached now that confirmBooking's transaction has
            // committed, so walking its lazy associations here would throw
            // LazyInitializationException. loadOwned initialises what the mapper
            // needs while its own session is still open.
            Booking forResponse = persistence.loadOwned(holdId, caller.id());
            BookingDetail detail = mapper.toDetail(forResponse);

            // ---- 4. Capture the money -------------------------------------
            payments.capture(authorizationId);
            authorizationId = null;   // captured; nothing left to void

            // ---- 5. Release the Redis hold --------------------------------
            // The seats are BOOKED in Postgres now, so the reservation has done
            // its job. Handing the keys back early keeps Redis tidy; failing to
            // would be harmless, since the TTL would clear them anyway.
            holds.release(eventId, seatIds, holdId);

            // ---- 6. Remember what we answered -----------------------------
            idempotency.storeResponse(claim.recordId(), 200, writeStored(detail), confirmed);

            log.info("Booking {} confirmed for user {}", confirmed.getReference(), caller.id());
            return detail;

        } catch (RuntimeException ex) {
            if (committed) {
                // The booking exists and is paid for. We failed on something
                // afterwards - rendering the response, capturing, or releasing
                // the hold. Compensating now would destroy a valid booking, so
                // we do not: we log loudly and rethrow. An operator has to see
                // this, because an uncaptured authorization is real money that
                // did not move.
                log.error("Booking {} committed but post-commit work failed. "
                        + "Authorization {} may need manual capture.",
                        holdId, authorizationId, ex);
                throw ex;
            }

            // Nothing committed. Undo the money first; voidAuthorization is
            // contractually non-throwing, so it cannot mask the original failure.
            if (authorizationId != null) {
                payments.voidAuthorization(authorizationId);
            }
            // Free the idempotency key so a retry genuinely re-runs rather than
            // replaying a failure forever - a customer who fixes their card must
            // be able to try again.
            idempotency.releaseClaim(claim.recordId());
            throw ex;
        }
    }

    // =====================================================================
    // READ / CANCEL
    // =====================================================================

    @Transactional(readOnly = true)
    public PageResponse<BookingSummary> listBookings(AuthenticatedUser caller, Pageable pageable) {
        Page<Booking> page = bookings.findByUser(caller.id(), pageable);
        List<BookingSummary> content = page.getContent().stream()
                .map(b -> mapper.toSummary(b, b.getSeats().size()))
                .toList();
        return PageResponse.of(content, page);
    }

    public BookingDetail getBooking(AuthenticatedUser caller, UUID publicId) {
        return mapper.toDetail(persistence.loadOwned(publicId, caller.id()));
    }

    public BookingDetail cancel(AuthenticatedUser caller, UUID publicId) {
        Booking booking = persistence.loadOwned(publicId, caller.id());
        Long eventId = booking.getEvent().getId();
        List<Long> seatIds = booking.getSeats().stream()
                .map(bs -> bs.getEventSeat().getId())
                .toList();

        persistence.cancelBooking(publicId, caller.id(), Instant.now());

        // Best-effort Redis cleanup. If the booking was PENDING its hold is
        // still live and should go now; if it was CONFIRMED the hold is long
        // gone and this is a no-op. Either way it runs after the database
        // commit, so a Redis hiccup cannot roll back a cancellation the user has
        // already been told about.
        holds.release(eventId, seatIds, publicId);

        return mapper.toDetail(persistence.loadOwned(publicId, caller.id()));
    }

    // =====================================================================

    /**
     * A stable string form of the request, for the idempotency hash.
     *
     * <p>Records serialise their components in declaration order, so the same
     * request always produces the same JSON and therefore the same hash. If this
     * were a {@code Map}, iteration order would vary and two identical retries
     * could hash differently - which would look like a key mismatch and reject a
     * perfectly valid retry.
     */
    private String canonicalise(ConfirmBookingRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not canonicalise confirm request", ex);
        }
    }

    private String writeStored(BookingDetail detail) {
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialise booking response", ex);
        }
    }

    private BookingDetail readStored(String body) {
        try {
            return objectMapper.readValue(body, BookingDetail.class);
        } catch (JsonProcessingException ex) {
            // A stored response we cannot read is a bug on our side, not the
            // client's. Better to fail loudly than to return something wrong.
            throw new IllegalStateException("Could not deserialise stored booking response", ex);
        }
    }
}
