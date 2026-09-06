package com.seatlock.repository;

import com.seatlock.domain.Event;
import com.seatlock.domain.EventCategory;
import com.seatlock.domain.EventStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long> {

    /**
     * The browse query.
     *
     * <p>Every filter is optional, expressed as {@code :param IS NULL OR ...}.
     * The alternative - building a SQL string by concatenation - is how SQL
     * injection happens. Here the parameters are bound by JDBC as values, so a
     * search term of {@code '; DROP TABLE users; --} is looked for as a literal
     * string and finds nothing, which is exactly right.
     *
     * <p>{@code LOWER(...) LIKE LOWER(CONCAT('%', :q, '%'))} is a deliberately
     * simple search. A leading wildcard cannot use a B-tree index, so this is a
     * sequential scan - fine at this scale, and the honest answer in an interview
     * is "at 100k events this becomes a Postgres full-text index or a search
     * engine; at 6 events it would be premature."
     */
    @Query(value = """
           SELECT e FROM Event e
             JOIN FETCH e.venue v
            WHERE e.status = :status
              AND (:category IS NULL OR e.category = :category)
              AND (:city     IS NULL OR LOWER(v.city) = LOWER(:city))
              AND (:q        IS NULL OR LOWER(e.title) LIKE LOWER(CONCAT('%', :q, '%'))
                                     OR LOWER(COALESCE(e.subtitle, '')) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY e.startsAt ASC
           """,
           countQuery = """
           SELECT COUNT(e) FROM Event e
            WHERE e.status = :status
              AND (:category IS NULL OR e.category = :category)
              AND (:city     IS NULL OR LOWER(e.venue.city) = LOWER(:city))
              AND (:q        IS NULL OR LOWER(e.title) LIKE LOWER(CONCAT('%', :q, '%'))
                                     OR LOWER(COALESCE(e.subtitle, '')) LIKE LOWER(CONCAT('%', :q, '%')))
           """)
    Page<Event> search(@Param("status") EventStatus status,
                       @Param("category") EventCategory category,
                       @Param("city") String city,
                       @Param("q") String q,
                       Pageable pageable);

    @Query("SELECT e FROM Event e JOIN FETCH e.venue WHERE e.id = :id")
    Optional<Event> findWithVenue(@Param("id") Long id);

    /** Cheapest tier price per event, for the "from Rs 280" label on a card. */
    @Query("""
           SELECT t.event.id, MIN(t.priceMinor)
             FROM PriceTier t
            WHERE t.event.id IN :eventIds
            GROUP BY t.event.id
           """)
    List<Object[]> minPriceByEventIds(@Param("eventIds") List<Long> eventIds);

    /** Distinct cities that currently have something published, for the picker. */
    @Query("""
           SELECT DISTINCT e.venue.city FROM Event e
            WHERE e.status = com.seatlock.domain.EventStatus.PUBLISHED
            ORDER BY e.venue.city
           """)
    List<String> findActiveCities();
}
