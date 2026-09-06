package com.seatlock.repository;

import com.seatlock.domain.EventSeat;
import com.seatlock.domain.SeatStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface EventSeatRepository extends JpaRepository<EventSeat, Long> {

    /**
     * Every seat of an event, ready to render, in one query.
     *
     * <p>The two {@code JOIN FETCH} clauses are the whole point. Without them,
     * this returns 200 {@code EventSeat} rows whose {@code seat} and
     * {@code priceTier} are lazy proxies; the moment the mapper reads
     * {@code seat.getRowLabel()} on each one, Hibernate fires another query.
     * 1 query becomes 401. That is the N+1 problem, and it is the most common
     * performance bug in JPA code.
     *
     * <p>Fetching two collections at once would be a different story - Hibernate
     * would have to produce a cartesian product - but these are both
     * {@code @ManyToOne}, so this is just a three-table join returning one row
     * per seat.
     *
     * <p>Ordering happens in SQL, not in Java, because the database can satisfy
     * it from an index and because sorting 200 rows in the JVM is work we would
     * be repeating on every request.
     */
    @Query("""
           SELECT es FROM EventSeat es
             JOIN FETCH es.seat s
             JOIN FETCH es.priceTier t
            WHERE es.event.id = :eventId
            ORDER BY s.rowIndex ASC, s.colIndex ASC
           """)
    List<EventSeat> findSeatMap(@Param("eventId") Long eventId);

    /**
     * Load a specific set of seats for an event, with their tier prices.
     *
     * <p>{@code eventId} is in the WHERE clause even though the ids alone would
     * identify the rows. That is an authorization check disguised as a filter:
     * it makes it impossible for a request against event 7 to hold a seat that
     * belongs to event 8 by passing its id. Never trust an id from a request
     * body to belong where the URL says it does.
     */
    @Query("""
           SELECT es FROM EventSeat es
             JOIN FETCH es.seat s
             JOIN FETCH es.priceTier t
            WHERE es.event.id = :eventId
              AND es.id IN :seatIds
           """)
    List<EventSeat> findForEvent(@Param("eventId") Long eventId,
                                 @Param("seatIds") Collection<Long> seatIds);

    /**
     * Just the ids, for the Redis MGET that decides which seats are currently
     * held. Selecting whole entities to throw away everything but the id would
     * be pure waste; a scalar projection keeps this an index-only scan.
     */
    @Query("SELECT es.id FROM EventSeat es WHERE es.event.id = :eventId")
    List<Long> findIdsByEventId(@Param("eventId") Long eventId);

    long countByEventIdAndStatus(Long eventId, SeatStatus status);

    /**
     * Aggregate seat counts per event, for the browse page.
     *
     * <p>Written as one grouped query rather than a {@code countByEventId} call
     * inside a loop over 20 event cards - same N+1 trap, different shape.
     * Returns {@code [eventId, availableCount, totalCount]} rows.
     */
    @Query("""
           SELECT es.event.id,
                  SUM(CASE WHEN es.status = com.seatlock.domain.SeatStatus.AVAILABLE THEN 1 ELSE 0 END),
                  COUNT(es)
             FROM EventSeat es
            WHERE es.event.id IN :eventIds
            GROUP BY es.event.id
           """)
    List<Object[]> countsByEventIds(@Param("eventIds") Collection<Long> eventIds);
}
