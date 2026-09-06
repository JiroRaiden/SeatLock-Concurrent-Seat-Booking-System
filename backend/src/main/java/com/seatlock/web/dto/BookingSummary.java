package com.seatlock.web.dto;

import java.time.Instant;
import java.util.UUID;

public record BookingSummary(
        UUID id,
        String reference,
        String status,
        Long totalMinor,
        int seatCount,
        Instant createdAt,
        Instant expiresAt,
        String eventTitle,
        Instant eventStartsAt,
        String venueName
) { }
