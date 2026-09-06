package com.seatlock.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * @param holdId      also the pending booking's public id. One identifier for
 *                    what is really one thing seen from two stores - the Redis
 *                    reservation and the Postgres row. It doubles as the
 *                    ownership token that makes releasing the Redis hold safe.
 * @param expiresAt   absolute instant, not a duration. A duration computed on
 *                    the server is already wrong by the network latency by the
 *                    time it arrives; an absolute instant lets the client run
 *                    its own clock against it.
 * @param ttlSeconds  sent anyway, purely so a client with a badly-set system
 *                    clock still shows a sane countdown.
 */
public record HoldResponse(
        UUID holdId,
        Long eventId,
        Instant expiresAt,
        long ttlSeconds,
        Long totalMinor,
        List<HeldSeatDto> seats
) { }
