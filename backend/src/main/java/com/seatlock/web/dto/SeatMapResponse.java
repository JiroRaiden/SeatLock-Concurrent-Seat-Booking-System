package com.seatlock.web.dto;

import java.time.Instant;
import java.util.List;

public record SeatMapResponse(
        Long eventId,
        String eventTitle,
        String venueName,
        Instant startsAt,
        Instant salesCloseAt,
        List<PriceTierDto> tiers,
        List<SeatMapRow> rows,
        SeatMapSummary summary
) { }
