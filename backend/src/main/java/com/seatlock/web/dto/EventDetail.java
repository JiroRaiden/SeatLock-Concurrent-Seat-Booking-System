package com.seatlock.web.dto;

import com.seatlock.domain.Event;

import java.time.Instant;
import java.util.List;

public record EventDetail(
        Long id,
        String title,
        String subtitle,
        String description,
        String category,
        String language,
        String certification,
        Short durationMinutes,
        String posterUrl,
        String backdropUrl,
        Instant startsAt,
        Instant salesCloseAt,
        String status,
        VenueSummary venue,
        String venueAddress,
        List<PriceTierDto> tiers,
        Long availableSeats,
        Long totalSeats
) {
    public static EventDetail from(Event e, List<PriceTierDto> tiers, Long available, Long total) {
        return new EventDetail(
                e.getId(), e.getTitle(), e.getSubtitle(), e.getDescription(),
                e.getCategory().name(), e.getLanguage(), e.getCertification(),
                e.getDurationMinutes(), e.getPosterUrl(), e.getBackdropUrl(),
                e.getStartsAt(), e.getSalesCloseAt(), e.getStatus().name(),
                VenueSummary.from(e.getVenue()), e.getVenue().getAddress(),
                tiers, available, total);
    }
}
