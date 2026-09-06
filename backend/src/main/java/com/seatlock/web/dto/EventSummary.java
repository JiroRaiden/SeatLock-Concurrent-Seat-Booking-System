package com.seatlock.web.dto;

import com.seatlock.domain.Event;

import java.time.Instant;

/**
 * A card on the browse page.
 *
 * @param minPriceMinor  the cheapest tier, for the "from Rs 280" label
 * @param availableSeats live count, so a card can show "Filling fast" or "Sold out"
 */
public record EventSummary(
        Long id,
        String title,
        String subtitle,
        String category,
        String language,
        String certification,
        Short durationMinutes,
        String posterUrl,
        Instant startsAt,
        Instant salesCloseAt,
        VenueSummary venue,
        Long minPriceMinor,
        Long availableSeats,
        Long totalSeats
) {
    /**
     * The counts are passed in rather than read off the entity, because getting
     * them per-event would mean a query per card - the N+1 problem in its most
     * expensive form, on the page with the most rows. The caller fetches all the
     * counts in one grouped query and hands them here.
     */
    public static EventSummary from(Event e, Long minPriceMinor, Long available, Long total) {
        return new EventSummary(
                e.getId(), e.getTitle(), e.getSubtitle(),
                e.getCategory().name(), e.getLanguage(), e.getCertification(),
                e.getDurationMinutes(), e.getPosterUrl(),
                e.getStartsAt(), e.getSalesCloseAt(),
                VenueSummary.from(e.getVenue()),
                minPriceMinor, available, total);
    }
}
