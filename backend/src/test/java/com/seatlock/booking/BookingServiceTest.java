package com.seatlock.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.seatlock.config.HoldProperties;
import com.seatlock.domain.Booking;
import com.seatlock.domain.BookingSeat;
import com.seatlock.domain.BookingStatus;
import com.seatlock.domain.Event;
import com.seatlock.domain.EventSeat;
import com.seatlock.domain.Role;
import com.seatlock.domain.User;
import com.seatlock.exception.ApiException;
import com.seatlock.exception.ErrorCode;
import com.seatlock.hold.HoldResult;
import com.seatlock.hold.SeatHoldService;
import com.seatlock.repository.BookingRepository;
import com.seatlock.repository.EventRepository;
import com.seatlock.repository.EventSeatRepository;
import com.seatlock.repository.UserRepository;
import com.seatlock.security.AuthenticatedUser;
import com.seatlock.web.dto.BookingDetail;
import com.seatlock.web.dto.BookingSeatDto;
import com.seatlock.web.dto.ConfirmBookingRequest;
import com.seatlock.web.dto.EventSummary;
import com.seatlock.web.dto.VenueSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link BookingService} with every collaborator replaced by a mock.
 *
 * <h2>What a test with no infrastructure can prove that an integration test cannot</h2>
 *
 * The integration tests prove the system produces the right <em>outcomes</em>.
 * They cannot easily prove the right <em>sequence</em>, because a passing
 * booking looks identical whether the payment was authorised before the database
 * commit or after it - right up to the day something fails in between, at which
 * point the difference is whether a customer was charged for a seat they did not
 * get.
 *
 * <p>That ordering is the entire design of this class (read its Javadoc first),
 * and mocks are the only practical way to assert it. Mocks also make failure
 * injectable: "the database rejected the insert" and "the commit lost an
 * optimistic lock" are one {@code thenThrow} here and an elaborate contrivance
 * against a real Postgres.
 *
 * <p>So the two styles are complementary rather than redundant. This file
 * asserts <b>what happens in what order, and what is undone when something
 * breaks</b>. {@link BookingLifecycleIT} asserts the same flow really works
 * against real infrastructure.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BookingService: orchestration order and compensating actions")
class BookingServiceTest {

    private static final Long EVENT_ID = 42L;
    private static final Long SEAT_ID = 911L;
    private static final Long CALLER_ID = 5L;

    private static final AuthenticatedUser CALLER =
            new AuthenticatedUser(CALLER_ID, "alice@test.dev", Role.ROLE_USER);

    @Mock private EventRepository events;
    @Mock private EventSeatRepository eventSeats;
    @Mock private BookingRepository bookings;
    @Mock private UserRepository users;
    @Mock private SeatHoldService holds;
    @Mock private BookingPersistence persistence;
    @Mock private IdempotencyService idempotency;
    @Mock private PaymentGateway payments;
    @Mock private BookingMapper mapper;

    private BookingService service;

    /**
     * Real, not mocked: it is used to serialise the stored idempotent response,
     * so a mock would only prove that a mock returns what it was told to.
     * {@link JavaTimeModule} is registered because {@code BookingDetail} carries
     * {@link Instant}s and a bare {@code ObjectMapper} cannot handle them - the
     * running application gets the same module from Spring Boot.
     */
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    /** Real: it is a value holder with validated defaults, and its real defaults are the rule under test. */
    private final HoldProperties holdProperties = new HoldProperties();

    @BeforeEach
    void buildService() {
        // Constructed by hand rather than with @InjectMocks. Eleven constructor
        // arguments is exactly the situation where @InjectMocks silently leaves
        // a field null if a type stops matching, and the resulting NPE points at
        // the wrong place entirely.
        service = new BookingService(events, eventSeats, bookings, users, holds, persistence,
                idempotency, payments, mapper, holdProperties, objectMapper);
    }

    // ==================================================================
    // HOLD
    // ==================================================================

    @Test
    @DisplayName("more seats than the configured maximum is refused before any I/O")
    void oversizedRequestIsRejectedWithoutTouchingAnything() {
        List<Long> tooMany = LongStream.rangeClosed(1, holdProperties.getMaxSeatsPerBooking() + 1)
                .boxed().toList();

        assertThatThrownBy(() -> service.createHold(CALLER, EVENT_ID, tooMany))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        // The cap is not politeness. An unbounded seat list would build an
        // unbounded Lua key list, and Redis executes scripts on a single thread
        // - one request could stall every other client on the server. Checking
        // it before any I/O means the cheapest possible rejection.
        verifyNoInteractions(events, eventSeats, holds, persistence, users);
    }

    @Test
    @DisplayName("an empty seat list is refused before any I/O")
    void emptyRequestIsRejectedWithoutTouchingAnything() {
        assertThatThrownBy(() -> service.createHold(CALLER, EVENT_ID, List.of()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        verifyNoInteractions(events, eventSeats, holds, persistence, users);
    }

    @Test
    @DisplayName("closed sales are refused server-side, and Redis is never consulted")
    void closedSalesAreRefusedBeforeReachingRedis() {
        Event event = mock(Event.class);
        when(events.findWithVenue(EVENT_ID)).thenReturn(Optional.of(event));
        when(event.isOpenForSale(any(Instant.class))).thenReturn(false);

        assertThatThrownBy(() -> service.createHold(CALLER, EVENT_ID, List.of(SEAT_ID)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.SALES_CLOSED));

        // The frontend hides the button once sales close, but anyone can POST
        // here directly. "The UI prevents it" is never an access control - and
        // the check has to come before the expensive work, not after it.
        verifyNoInteractions(holds, persistence, payments);
    }

    @Test
    @DisplayName("when the hold is rejected, nothing is written to the database")
    void aRejectedHoldWritesNothing() {
        Event event = openEvent();
        when(events.findWithVenue(EVENT_ID)).thenReturn(Optional.of(event));
        when(eventSeats.findForEvent(eq(EVENT_ID), anyCollection()))
                .thenReturn(List.of(availableSeat(SEAT_ID)));
        when(holds.acquire(anyLong(), anyList(), any(UUID.class), any(Duration.class)))
                .thenReturn(HoldResult.rejected(List.of(SEAT_ID)));

        assertThatThrownBy(() -> service.createHold(CALLER, EVENT_ID, List.of(SEAT_ID)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.SEAT_UNAVAILABLE));

        // Losing a contested seat is an ordinary outcome, not an error state. It
        // must leave no trace at all: a PENDING booking behind a hold we do not
        // own would claim the seat through booking_seats for eight minutes on
        // behalf of a user who never got it.
        verify(persistence, never()).createPendingBooking(any(), any(), anyList(), any(), any());
        verifyNoInteractions(payments);
    }

    /**
     * <h2>The most valuable test in this file.</h2>
     *
     * Redis and Postgres cannot share a transaction. The hold has to be taken
     * <em>before</em> the booking row is written - otherwise a row exists with no
     * reservation behind it and the seat can be sold twice - which means there is
     * a window where the reservation succeeded and the write did not.
     *
     * <p>Without compensation, that window costs a seat: Redis would keep the
     * lease for the full eight minutes on behalf of a booking that does not
     * exist, and no code path would ever come back to clean it up. During a busy
     * drop, every failed insert would quietly retire a seat from sale.
     *
     * <p>The compensating {@code release} is what closes it, and the token is
     * what makes the compensation safe: {@code release} is a compare-and-delete
     * on our own token, so even if we are confused about the state, we cannot
     * delete somebody else's lease. Hence the assertion is not merely "release
     * was called" but "release was called <b>with the same token we acquired
     * with</b>" - a release with a fresh UUID would silently do nothing, and a
     * release with no token check would be the classic distributed-lock bug.
     */
    @Test
    @DisplayName("a failed database write releases the Redis hold, with the same token")
    void aFailedPersistCompensatesByReleasingTheHold() {
        Event event = openEvent();
        when(events.findWithVenue(EVENT_ID)).thenReturn(Optional.of(event));
        when(eventSeats.findForEvent(eq(EVENT_ID), anyCollection()))
                .thenReturn(List.of(availableSeat(SEAT_ID)));
        when(holds.acquire(anyLong(), anyList(), any(UUID.class), any(Duration.class)))
                .thenReturn(HoldResult.acquired(List.of(SEAT_ID)));
        when(users.getReferenceById(any())).thenReturn(mock(User.class));

        // The partial unique index refused a second live claim on the seat -
        // the database having the last word, exactly as designed.
        when(persistence.createPendingBooking(any(), any(), anyList(), any(UUID.class), any(Instant.class)))
                .thenThrow(new DataIntegrityViolationException("booking_seats_one_active_per_seat"));

        assertThatThrownBy(() -> service.createHold(CALLER, EVENT_ID, List.of(SEAT_ID)))
                .as("the original failure must propagate, not be swallowed by the cleanup")
                .isInstanceOf(DataIntegrityViolationException.class);

        ArgumentCaptor<UUID> acquiredWith = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<UUID> releasedWith = ArgumentCaptor.forClass(UUID.class);

        // The order is the property: acquire, then try to persist, then - and
        // only then - release. A release before the attempt would be pointless;
        // a release that never happens is the leak this test exists to prevent.
        InOrder ordered = inOrder(holds, persistence);
        ordered.verify(holds).acquire(anyLong(), anyList(), acquiredWith.capture(), any(Duration.class));
        ordered.verify(persistence).createPendingBooking(any(), any(), anyList(), any(UUID.class), any(Instant.class));
        ordered.verify(holds).release(anyLong(), anyCollection(), releasedWith.capture());

        assertThat(releasedWith.getValue())
                .as("releasing with any other token would be a no-op, and the seat would stay locked")
                .isEqualTo(acquiredWith.getValue());
    }

    // ==================================================================
    // CONFIRM
    // ==================================================================

    @Test
    @DisplayName("confirm authorises before committing, and captures only afterwards")
    void confirmAuthorisesBeforeCommittingAndCapturesAfter() throws Exception {
        UUID holdId = UUID.randomUUID();
        BookingDetail detail = sampleDetail(holdId);

        when(users.getReferenceById(any())).thenReturn(mock(User.class));
        when(idempotency.claim(any(), anyString(), anyString(), anyString()))
                .thenReturn(IdempotencyService.Claim.proceed(77L));
        when(persistence.loadOwned(holdId, CALLER_ID)).thenReturn(pendingBooking(holdId));
        when(holds.ownsAll(anyLong(), anyCollection(), any(UUID.class))).thenReturn(true);
        when(payments.authorize(anyString(), anyLong(), anyString()))
                .thenReturn(PaymentGateway.PaymentResult.approved("auth_ok"));
        when(persistence.confirmBooking(any(UUID.class), any(), any(Instant.class)))
                .thenReturn(pendingBooking(holdId));
        when(mapper.toDetail(any())).thenReturn(detail);

        BookingDetail returned = service.confirm(CALLER, holdId, cardPayment(), "idem-key");

        assertThat(returned).isEqualTo(detail);

        // This ordering is the whole risk calculation of the class, written out
        // as an assertion:
        //   ownsAll  - refuse early if the lease lapsed while the user was paying
        //   authorize- reserve the money; nothing has moved yet
        //   commit   - the point of no return
        //   capture  - take the money only once the seat is definitely theirs
        //   release  - hand the lease back; the seat is BOOKED in Postgres now
        // Capturing before the commit would mean charging for a booking that can
        // still fail. Authorising after it would mean giving away a seat before
        // knowing the card works.
        InOrder ordered = inOrder(holds, payments, persistence, idempotency);
        ordered.verify(holds).ownsAll(anyLong(), anyCollection(), any(UUID.class));
        ordered.verify(payments).authorize(anyString(), anyLong(), anyString());
        ordered.verify(persistence).confirmBooking(any(UUID.class), any(), any(Instant.class));
        ordered.verify(payments).capture("auth_ok");
        ordered.verify(holds).release(anyLong(), anyCollection(), any(UUID.class));
        ordered.verify(idempotency).storeResponse(eq(77L), eq(200), anyString(), any());

        verify(payments, never()).voidAuthorization(anyString());
    }

    @Test
    @DisplayName("when the commit fails, the authorisation is voided and the key is released")
    void aFailedCommitVoidsTheAuthorisationAndFreesTheKey() {
        UUID holdId = UUID.randomUUID();

        when(users.getReferenceById(any())).thenReturn(mock(User.class));
        when(idempotency.claim(any(), anyString(), anyString(), anyString()))
                .thenReturn(IdempotencyService.Claim.proceed(77L));
        when(persistence.loadOwned(holdId, CALLER_ID)).thenReturn(pendingBooking(holdId));
        when(holds.ownsAll(anyLong(), anyCollection(), any(UUID.class))).thenReturn(true);
        when(payments.authorize(anyString(), anyLong(), anyString()))
                .thenReturn(PaymentGateway.PaymentResult.approved("auth_doomed"));
        when(persistence.confirmBooking(any(UUID.class), any(), any(Instant.class)))
                .thenThrow(new OptimisticLockingFailureException("somebody booked the seat first"));

        assertThatThrownBy(() -> service.confirm(CALLER, holdId, cardPayment(), "idem-key"))
                .isInstanceOf(OptimisticLockingFailureException.class);

        InOrder ordered = inOrder(payments, idempotency);

        // Undo the money FIRST. voidAuthorization is contractually non-throwing
        // precisely so it cannot mask the original failure on the way out; if it
        // ran second and the key release threw, the customer would be left with
        // money reserved on their card for a booking that never existed.
        ordered.verify(payments).voidAuthorization("auth_doomed");

        // Then give the idempotency key back, because nothing was committed. A
        // retained key would replay this failure forever - the customer fixes
        // their card, retries, and gets the same refusal with no way out.
        ordered.verify(idempotency).releaseClaim(77L);

        verify(payments, never()).capture(anyString());
    }

    @Test
    @DisplayName("a REPLAY outcome returns the stored response and never touches the gateway")
    void replayReturnsTheStoredResponseWithoutCharging() throws Exception {
        UUID holdId = UUID.randomUUID();
        BookingDetail stored = sampleDetail(holdId);

        when(users.getReferenceById(any())).thenReturn(mock(User.class));
        when(idempotency.claim(any(), anyString(), anyString(), anyString()))
                .thenReturn(new IdempotencyService.Claim(
                        IdempotencyService.Outcome.REPLAY, 5L, (short) 200,
                        objectMapper.writeValueAsString(stored)));

        BookingDetail returned = service.confirm(CALLER, holdId, cardPayment(), "idem-key");

        assertThat(returned)
                .as("a retry must get back the answer we already gave")
                .isEqualTo(stored);

        // The point of idempotency on this endpoint is not tidiness, it is that
        // a client retrying after a network timeout must not be charged twice.
        // Reaching the gateway at all would defeat it.
        verifyNoInteractions(payments);
        verifyNoInteractions(persistence);
        verifyNoInteractions(holds);
    }

    // ==================================================================
    // Fixtures
    // ==================================================================

    private ConfirmBookingRequest cardPayment() {
        return new ConfirmBookingRequest("CARD", "tok_demo_success");
    }

    private Event openEvent() {
        Event event = mock(Event.class);
        when(event.isOpenForSale(any(Instant.class))).thenReturn(true);
        return event;
    }

    /**
     * {@code lenient()} because which of these getters gets called depends on
     * which branch the test drives the service down. Strict stubbing would
     * otherwise fail a perfectly correct test for the crime of setting up a
     * value the code did not need on that path.
     */
    private EventSeat availableSeat(Long id) {
        EventSeat seat = mock(EventSeat.class);
        lenient().when(seat.getId()).thenReturn(id);
        lenient().when(seat.isAvailable()).thenReturn(true);
        return seat;
    }

    private Booking pendingBooking(UUID holdId) {
        Event event = mock(Event.class);
        lenient().when(event.getId()).thenReturn(EVENT_ID);

        EventSeat eventSeat = mock(EventSeat.class);
        lenient().when(eventSeat.getId()).thenReturn(SEAT_ID);

        BookingSeat line = mock(BookingSeat.class);
        lenient().when(line.getEventSeat()).thenReturn(eventSeat);

        Booking booking = mock(Booking.class);
        lenient().when(booking.getPublicId()).thenReturn(holdId);
        lenient().when(booking.getStatus()).thenReturn(BookingStatus.PENDING);
        lenient().when(booking.isExpired(any(Instant.class))).thenReturn(false);
        lenient().when(booking.getSeats()).thenReturn(List.of(line));
        lenient().when(booking.getEvent()).thenReturn(event);
        lenient().when(booking.getTotalMinor()).thenReturn(45_000L);
        lenient().when(booking.getReference()).thenReturn("SL-TEST01");
        return booking;
    }

    private BookingDetail sampleDetail(UUID holdId) {
        return new BookingDetail(
                holdId,
                "SL-TEST01",
                "CONFIRMED",
                45_000L,
                Instant.parse("2026-01-01T10:00:00Z"),
                null,
                Instant.parse("2026-01-01T10:01:00Z"),
                null,
                new EventSummary(1L, "Test Show", null, "MOVIE", "English", "UA",
                        (short) 120, null,
                        Instant.parse("2026-01-01T18:00:00Z"),
                        Instant.parse("2026-01-01T17:30:00Z"),
                        new VenueSummary(1L, "Test Venue", "Kolkata"),
                        null, null, null),
                List.of(new BookingSeatDto(SEAT_ID, "A1", "PRIME", 45_000L)));
    }
}
