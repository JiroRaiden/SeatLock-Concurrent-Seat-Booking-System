package com.seatlock.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BookingDetail(
        UUID id,
        String reference,
        String status,
        Long totalMinor,
        Instant createdAt,
        Instant expiresAt,
        Instant confirmedAt,
        Instant cancelledAt,
        EventSummary event,
        List<BookingSeatDto> seats
) { }
