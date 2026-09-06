package com.seatlock.repository;

import com.seatlock.domain.Booking;
import com.seatlock.domain.BookingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    /**
     * Load a booking by its public UUID <b>and</b> its owner in a single query.
     *
     * <p>This signature is a security decision. The tempting version is:
     *
     * <pre>
     *   Booking b = repo.findByPublicId(id).orElseThrow();
     *   if (!b.getUser().getId().equals(currentUserId)) throw new Forbidden();
     * </pre>
     *
     * <p>which works right up until somebody adds a new endpoint and forgets the
     * second line. Putting the owner in the WHERE clause means "not found" and
     * "not yours" are the same code path, so there is no check left to forget.
     * It also gives the desired 404-rather-than-403 behaviour for free: an
     * attacker probing booking ids cannot tell an id that does not exist from one
     * that belongs to somebody else.
     */
    @Query("""
           SELECT b FROM Booking b
             JOIN FETCH b.event e
             JOIN FETCH e.venue v
            WHERE b.publicId = :publicId
              AND b.user.id  = :userId
           """)
    Optional<Booking> findOwned(@Param("publicId") UUID publicId, @Param("userId") Long userId);

    /**
     * Same lookup without the ownership filter - for the reaper and for admin
     * paths only. Named so that using it by accident in a user-facing handler
     * reads as obviously wrong in review.
     */
    Optional<Booking> findByPublicId(UUID publicId);

    @Query(value = """
           SELECT b FROM Booking b
             JOIN FETCH b.event e
             JOIN FETCH e.venue v
            WHERE b.user.id = :userId
            ORDER BY b.createdAt DESC
           """,
           countQuery = "SELECT COUNT(b) FROM Booking b WHERE b.user.id = :userId")
    Page<Booking> findByUser(@Param("userId") Long userId, Pageable pageable);

    /**
     * The reaper's query: PENDING bookings whose hold has already lapsed.
     *
     * <p>{@code LIMIT} via {@link Pageable} rather than fetching everything.
     * If the service were down for an hour there could be tens of thousands of
     * these, and loading them all into one transaction would be a memory spike
     * and a very long-running transaction holding back Postgres's vacuum.
     * Sweeping in bounded batches keeps each transaction short.
     */
    @Query("""
           SELECT b FROM Booking b
            WHERE b.status = :status
              AND b.expiresAt IS NOT NULL
              AND b.expiresAt < :now
            ORDER BY b.expiresAt ASC
           """)
    List<Booking> findExpiredBatch(@Param("status") BookingStatus status,
                                   @Param("now") Instant now,
                                   Pageable pageable);

    boolean existsByReference(String reference);

    /**
     * Expire, right now, any already-lapsed PENDING booking that is still
     * claiming one of these seats.
     *
     * <h2>Why this exists - a real failure mode, not a hypothetical</h2>
     *
     * A PENDING booking writes {@code booking_seats} rows with {@code active = true},
     * so the partial unique index treats the seat as claimed even before payment.
     * Redis and Postgres then expire on different schedules:
     *
     * <pre>
     *   t=8m00s  Redis TTL fires. The seat is free as far as Redis is concerned.
     *   t=8m01s  Bob's hold succeeds in Redis...
     *            ...but his INSERT into booking_seats hits the unique index,
     *            because Alice's abandoned PENDING row is still active.
     *   t=8m45s  The background reaper finally runs and expires Alice's booking.
     * </pre>
     *
     * <p>For those 45 seconds the seat looks taken to everyone while belonging to
     * nobody. Sweeping on a timer alone cannot close that window; it can only
     * make it shorter, and shortening it means running the sweep more often for
     * no benefit the rest of the time.
     *
     * <p>So we also clean up <em>on demand</em>: whenever someone tries to hold a
     * seat, first expire anything stale that is standing in the way. The
     * background reaper stays, because seats nobody is asking for still need
     * tidying, but the user-visible window closes to zero.
     *
     * <p>Note {@code b.version = b.version + 1}: a bulk JPQL update bypasses
     * Hibernate's optimistic locking entirely, so without this an in-flight
     * confirmation could commit over the top of the expiry. Incrementing the
     * version by hand keeps the {@code @Version} contract intact.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           UPDATE Booking b
              SET b.status = com.seatlock.domain.BookingStatus.EXPIRED,
                  b.cancelledAt = :now,
                  b.version = b.version + 1
            WHERE b.status = com.seatlock.domain.BookingStatus.PENDING
              AND b.expiresAt IS NOT NULL
              AND b.expiresAt < :now
              AND b.id IN (
                    SELECT bs.booking.id FROM BookingSeat bs
                     WHERE bs.active = true
                       AND bs.eventSeat.id IN :seatIds
              )
           """)
    int expireStalePendingClaiming(@Param("seatIds") Collection<Long> seatIds,
                                   @Param("now") Instant now);
}
