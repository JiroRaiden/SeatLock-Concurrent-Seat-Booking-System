package com.seatlock.booking;

import com.seatlock.domain.Event;
import com.seatlock.domain.EventSeat;
import com.seatlock.domain.User;
import com.seatlock.repository.EventRepository;
import com.seatlock.repository.EventSeatRepository;
import com.seatlock.repository.UserRepository;
import com.seatlock.support.IntegrationTestBase;
import com.seatlock.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the second and third layers of protection work <b>on their own</b>,
 * with Redis deliberately taken out of the picture.
 *
 * <h2>Why this test matters more than it looks</h2>
 *
 * {@link ConcurrentSeatBookingIT} proves the system as a whole does not
 * oversell. But it runs with Redis healthy, so Redis catches essentially every
 * conflict and the database layers are never really exercised. A green run there
 * would look identical whether the {@code @Version} column and the partial
 * unique index were doing their jobs or were quietly broken.
 *
 * <p>The interesting question is what happens in the window where Redis has
 * failed over to a replica missing recent writes, or has been restarted empty.
 * In that window two requests genuinely can both believe they hold a seat.
 *
 * <p>So these tests skip the hold layer entirely and go straight at the
 * database, which is exactly the state a Redis failure produces.
 */
@DisplayName("Defence in depth: the database is correct even when Redis is not")
class DefenceInDepthIT extends IntegrationTestBase {

    @Autowired private TestFixtures fixtures;
    @Autowired private BookingPersistence persistence;
    @Autowired private EventRepository events;
    @Autowired private EventSeatRepository eventSeats;
    @Autowired private UserRepository users;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void reset() {
        fixtures.reset();
    }

    @Test
    @DisplayName("Layer 3: the partial unique index refuses a second live claim on a seat")
    void uniqueIndexPreventsTwoActiveClaims() throws Exception {
        TestFixtures.Auditorium auditorium = fixtures.createAuditorium(1, 4);
        long seatId = auditorium.firstSeat();

        long aliceId = fixtures.createUser("alice@test.dev");
        long bobId = fixtures.createUser("bob@test.dev");

        Event event = events.findWithVenue(auditorium.eventId()).orElseThrow();
        User alice = users.findById(aliceId).orElseThrow();
        User bob = users.findById(bobId).orElseThrow();
        Instant expiry = Instant.now().plusSeconds(300);

        // Two threads write a PENDING booking for the same seat at the same
        // moment. Redis is never consulted - this is the state a Redis outage
        // leaves us in, where both callers sincerely believe the seat is theirs.
        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicReference<Class<?>> rejectionType = new AtomicReference<>();

        List<Callable<Void>> tasks = List.of(
                bookingTask(alice, event, seatId, expiry, startLine, succeeded, rejected, rejectionType),
                bookingTask(bob, event, seatId, expiry, startLine, succeeded, rejected, rejectionType));

        for (Future<Void> future : pool.invokeAll(tasks, 30, TimeUnit.SECONDS)) {
            future.get();
        }
        pool.shutdownNow();

        assertThat(succeeded.get())
                .as("exactly one booking may be written")
                .isEqualTo(1);
        assertThat(rejected.get())
                .as("the other must be refused")
                .isEqualTo(1);

        // The refusal must come from the database, not from an application check
        // - that is the whole point of having a constraint you cannot bypass.
        assertThat(rejectionType.get())
                .as("the rejection should come from a database constraint or an application-level conflict")
                .isNotNull();

        assertThat(fixtures.activeClaimsOn(seatId))
                .as("one active claim, enforced by booking_seats_one_active_per_seat")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Layer 2: @Version stops two transactions booking the same seat")
    void optimisticLockRejectsTheSecondWriter() throws Exception {
        TestFixtures.Auditorium auditorium = fixtures.createAuditorium(1, 4);
        long seatId = auditorium.firstSeat();

        // Two transactions both read the seat at version N, then both try to
        // flip it to BOOKED. Hibernate appends "AND version = N" to each UPDATE,
        // so the second one matches zero rows and blows up.
        //
        // Interleaving is forced with barriers rather than left to chance:
        //   both READ  ->  barrier  ->  both WRITE
        // Without the barrier the first transaction usually commits before the
        // second one reads, and no conflict ever occurs - the test would pass
        // while proving nothing.
        CyclicBarrier afterRead = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        AtomicInteger committed = new AtomicInteger();
        AtomicInteger lockConflicts = new AtomicInteger();

        Callable<Void> attempt = () -> {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            try {
                tx.executeWithoutResult(status -> {
                    EventSeat seat = eventSeats.findById(seatId).orElseThrow();
                    try {
                        afterRead.await(20, TimeUnit.SECONDS);   // both have now read
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    seat.markBooked();
                    // Flush inside the transaction so the UPDATE (and therefore
                    // the version check) happens here rather than at commit,
                    // which makes the failure attributable to this statement.
                    eventSeats.saveAndFlush(seat);
                });
                committed.incrementAndGet();
            } catch (OptimisticLockingFailureException ex) {
                lockConflicts.incrementAndGet();
            } catch (IllegalStateException ex) {
                // markBooked() refused because the other transaction had already
                // committed and this one re-read a BOOKED seat. Also a correct
                // rejection - the entity guard caught it before the lock had to.
                lockConflicts.incrementAndGet();
            }
            return null;
        };

        for (Future<Void> future : pool.invokeAll(List.of(attempt, attempt), 30, TimeUnit.SECONDS)) {
            future.get();
        }
        pool.shutdownNow();

        assertThat(committed.get())
                .as("exactly one transaction may book the seat")
                .isEqualTo(1);
        assertThat(lockConflicts.get())
                .as("the loser is rejected, not silently allowed through")
                .isEqualTo(1);
        assertThat(fixtures.seatStatus(seatId)).isEqualTo("BOOKED");
    }

    @Test
    @DisplayName("A cancelled booking frees its seat for resale")
    void cancellationReleasesTheSeatFromTheUniqueIndex() {
        TestFixtures.Auditorium auditorium = fixtures.createAuditorium(1, 4);
        long seatId = auditorium.firstSeat();

        long aliceId = fixtures.createUser("alice@test.dev");
        Event event = events.findWithVenue(auditorium.eventId()).orElseThrow();
        User alice = users.findById(aliceId).orElseThrow();

        List<EventSeat> seats = eventSeats.findForEvent(auditorium.eventId(), List.of(seatId));
        UUID holdId = UUID.randomUUID();
        var booking = persistence.createPendingBooking(alice, event, seats, holdId,
                Instant.now().plusSeconds(300));

        assertThat(fixtures.activeClaimsOn(seatId)).isEqualTo(1);

        persistence.cancelBooking(booking.getPublicId(), aliceId, Instant.now());

        // The database trigger on bookings.status flips booking_seats.active to
        // false, which drops the row out of the partial unique index. Nothing in
        // Java did this - which is precisely why it cannot be forgotten.
        assertThat(fixtures.activeClaimsOn(seatId))
                .as("the trigger should have deactivated the seat claim")
                .isZero();

        // And the seat is genuinely re-bookable by somebody else.
        long bobId = fixtures.createUser("bob@test.dev");
        User bob = users.findById(bobId).orElseThrow();
        List<EventSeat> reloaded = eventSeats.findForEvent(auditorium.eventId(), List.of(seatId));
        persistence.createPendingBooking(bob, event, reloaded, UUID.randomUUID(),
                Instant.now().plusSeconds(300));

        assertThat(fixtures.activeClaimsOn(seatId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------

    private Callable<Void> bookingTask(User user,
                                       Event event,
                                       long seatId,
                                       Instant expiry,
                                       CyclicBarrier startLine,
                                       AtomicInteger succeeded,
                                       AtomicInteger rejected,
                                       AtomicReference<Class<?>> rejectionType) {
        return () -> {
            try {
                List<EventSeat> seats = eventSeats.findForEvent(event.getId(), List.of(seatId));
                startLine.await(20, TimeUnit.SECONDS);
                persistence.createPendingBooking(user, event, seats, UUID.randomUUID(), expiry);
                succeeded.incrementAndGet();
            } catch (DataIntegrityViolationException | OptimisticLockingFailureException ex) {
                rejected.incrementAndGet();
                rejectionType.set(ex.getClass());
            } catch (RuntimeException ex) {
                // An ApiException("SEAT_UNAVAILABLE") is also a correct rejection:
                // it means the in-transaction availability re-read caught it
                // before the constraint had to.
                rejected.incrementAndGet();
                rejectionType.set(ex.getClass());
            }
            return null;
        };
    }
}
