package com.seatlock.repository;

import com.seatlock.domain.PriceTier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PriceTierRepository extends JpaRepository<PriceTier, Long> {

    /**
     * Derived query: Spring Data parses the method name into
     * {@code WHERE event_id = ? ORDER BY sort_order ASC}. Fine for something
     * this simple - anything with a join or a condition worth explaining gets a
     * written-out {@code @Query} instead, because a method name long enough to
     * express it is no longer readable.
     */
    List<PriceTier> findByEventIdOrderBySortOrderAsc(Long eventId);
}
