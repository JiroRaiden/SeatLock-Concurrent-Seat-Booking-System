package com.seatlock.web.dto;

import java.time.Instant;

public record ExtendHoldResponse(Instant expiresAt, long ttlSeconds) { }
