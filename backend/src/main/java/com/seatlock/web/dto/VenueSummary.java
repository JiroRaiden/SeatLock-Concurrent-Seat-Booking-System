package com.seatlock.web.dto;

import com.seatlock.domain.Venue;

public record VenueSummary(Long id, String name, String city) {
    public static VenueSummary from(Venue venue) {
        return new VenueSummary(venue.getId(), venue.getName(), venue.getCity());
    }
}
