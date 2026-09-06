package com.seatlock.web.dto;

/**
 * @param accessToken       short-lived JWT for the Authorization header
 * @param refreshToken      opaque, long-lived, single-use; rotated on every refresh
 * @param tokenType         always "Bearer"; present so clients need not hardcode it
 * @param expiresInSeconds  lifetime of the ACCESS token, so a client can refresh
 *                          proactively instead of waiting to be surprised by a 401
 * @param user              saves the client an immediate follow-up call to /auth/me
 */
public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresInSeconds,
        UserSummary user
) { }
