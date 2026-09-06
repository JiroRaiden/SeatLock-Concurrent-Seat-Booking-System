package com.seatlock.hold;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

/**
 * Temporary seat reservations, held in Redis with a time-to-live.
 *
 * <h2>What this class is for</h2>
 *
 * When a user presses <i>Proceed</i>, we must take their seats off the market
 * immediately, keep them off for a few minutes, and give them back automatically
 * if the user never pays. That is a distributed lock with a lease, and it is the
 * fast path of the whole system: it runs on every checkout, it decides who wins
 * a contested seat, and it must answer in single-digit milliseconds.
 *
 * <h2>Why Redis rather than the database</h2>
 *
 * The database could do this. It would be wrong, for three reasons.
 *
 * <ol>
 *   <li><b>Lock duration.</b> A pessimistic lock ({@code SELECT ... FOR UPDATE})
 *       is held until the transaction ends. Here the "transaction" includes a
 *       human typing card details and waiting for an OTP - seconds to minutes.
 *       Holding a Postgres row lock that long parks a connection from a pool of
 *       twenty and blocks every other reader of that row. A few hundred
 *       concurrent checkouts would exhaust the pool and take the whole service
 *       down, not just the contested seats.</li>
 *   <li><b>Expiry is free.</b> Redis deletes the key itself when the TTL
 *       elapses. A database equivalent needs a status column, a timestamp, and a
 *       sweeper job - and if that job is down, seats stay locked. Here, the
 *       failure mode of "our cleanup broke" is "nothing, Redis already did it".</li>
 *   <li><b>Cost per operation.</b> A hold is a write that we expect to throw
 *       away most of the time. Doing it in-memory, with no WAL, no MVCC row
 *       version, and no vacuum afterwards, is roughly two orders of magnitude
 *       cheaper than doing it in Postgres.</li>
 * </ol>
 *
 * <h2>What this class is NOT</h2>
 *
 * It is not the thing that guarantees correctness. Redis is a cache with
 * persistence bolted on: it can fail over to a replica that is missing the last
 * few writes, and a restart with an empty dataset releases every hold at once.
 * In those windows two users can both believe they hold seat A5.
 *
 * <p>So this layer's job is to make conflicts <b>rare and cheap</b>. Making the
 * rare case <b>correct</b> is the database's job, via the {@code @Version}
 * optimistic lock on {@code event_seats} and the partial unique index on
 * {@code booking_seats}. Fast and safe are different mechanisms on purpose -
 * anything fast enough for the hot path cannot also be the durable record.
 *
 * @see com.seatlock.domain.EventSeat  the optimistic lock
 * @see com.seatlock.domain.BookingSeat  the database-level oversell guarantee
 */
@Service
public class SeatHoldService {

    private static final Logger log = LoggerFactory.getLogger(SeatHoldService.class);

    /**
     * Key layout: {@code seatlock:hold:{evt:42}:9137}
     *
     * <ul>
     *   <li>{@code seatlock:} namespaces us away from anything else sharing the
     *       Redis instance. Sharing a Redis and colliding on a key name is a
     *       genuinely painful outage to diagnose.</li>
     *   <li>{@code {evt:42}} - the braces are a Redis Cluster <b>hash tag</b>.
     *       Cluster hashes only the text inside braces to pick a shard, so every
     *       seat of event 42 lands on one node. Our Lua scripts touch many keys
     *       at once, and Cluster rejects multi-key commands that span shards, so
     *       this is what keeps a future move to Cluster from being a rewrite.
     *       On a single node it is simply part of the key name and costs
     *       nothing.</li>
     *   <li>the trailing id is the {@code event_seats.id}, which is already
     *       unique per (event, seat).</li>
     * </ul>
     */
    private static final String KEY_PREFIX = "seatlock:hold:";

    private final StringRedisTemplate redis;
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> acquireScript;
    private final RedisScript<Long> releaseScript;
    private final RedisScript<Long> extendScript;

    @SuppressWarnings("rawtypes")
    public SeatHoldService(StringRedisTemplate redis,
                           RedisScript<List> acquireHoldsScript,
                           RedisScript<Long> releaseHoldsScript,
                           RedisScript<Long> extendHoldsScript) {
        this.redis = redis;
        this.acquireScript = acquireHoldsScript;
        this.releaseScript = releaseHoldsScript;
        this.extendScript = extendHoldsScript;
    }

    static String keyFor(long eventId, long eventSeatId) {
        return KEY_PREFIX + "{evt:" + eventId + "}:" + eventSeatId;
    }

    /**
     * Try to reserve every one of {@code eventSeatIds} for {@code token}.
     *
     * <p>All-or-nothing. If even one seat is held by somebody else, nothing is
     * reserved and the returned result names the seats that were lost. That
     * matters: a partial reservation would take seats off sale for a booking
     * that can never complete, and the user would have to be told "you got 3 of
     * your 4 seats, in different parts of the room".
     *
     * <p>Re-calling with the same token is safe and refreshes the TTL, so a
     * client retrying after a network timeout is not treated as a competitor.
     *
     * @param eventId      the event, used for the key's hash tag
     * @param eventSeatIds the seats to hold; order is preserved so that returned
     *                     conflict indices can be mapped back to ids
     * @param token        the owner's identity - in this system, the pending
     *                     booking's public UUID. Never reuse a token across
     *                     bookings; it is what makes release safe.
     * @param ttl          how long the reservation lasts
     */
    public HoldResult acquire(long eventId, List<Long> eventSeatIds, UUID token, Duration ttl) {
        Objects.requireNonNull(token, "token");
        if (eventSeatIds == null || eventSeatIds.isEmpty()) {
            throw new IllegalArgumentException("Cannot acquire a hold over zero seats");
        }

        // Sorting is not cosmetic. Two requests that both want seats {5, 9} but
        // in different orders would, with a naive lock-one-at-a-time approach,
        // deadlock: A holds 5 and wants 9 while B holds 9 and wants 5. A global
        // ordering makes that impossible. Our Lua script is atomic so it cannot
        // deadlock anyway, but keeping a canonical order costs nothing and means
        // the invariant survives if this is ever reimplemented without Lua.
        List<Long> ordered = eventSeatIds.stream().distinct().sorted().toList();

        List<String> keys = ordered.stream()
                .map(seatId -> keyFor(eventId, seatId))
                .toList();

        // The script returns a Lua table of 1-based indices of the keys that were
        // already taken, or an empty table on success.
        @SuppressWarnings("unchecked")
        List<Long> conflictIndices = (List<Long>) redis.execute(
                acquireScript,
                keys,
                token.toString(),
                String.valueOf(ttl.toMillis()));

        if (conflictIndices == null || conflictIndices.isEmpty()) {
            log.debug("Acquired {} seat(s) for event {} under token {}", ordered.size(), eventId, token);
            return HoldResult.acquired(ordered);
        }

        // Map Lua's 1-based indices back to the seat ids the caller asked about.
        List<Long> conflicting = conflictIndices.stream()
                .map(i -> ordered.get(i.intValue() - 1))
                .toList();

        log.debug("Hold rejected for event {}: seats {} already held", eventId, conflicting);
        return HoldResult.rejected(conflicting);
    }

    /**
     * Hand the seats back - but only the ones this token still owns.
     *
     * <p>Safe to call twice, safe to call on an already-expired hold, and safe
     * to call from a {@code catch} block. That is deliberate: this is the
     * compensating action when the database write after a successful hold fails,
     * and a cleanup path that can itself throw is not much of a cleanup path.
     *
     * @return how many keys were actually deleted. Fewer than requested means
     *         some had already expired, which is normal, not an error.
     */
    public long release(long eventId, Collection<Long> eventSeatIds, UUID token) {
        if (eventSeatIds == null || eventSeatIds.isEmpty()) {
            return 0;
        }
        List<String> keys = eventSeatIds.stream()
                .distinct().sorted()
                .map(seatId -> keyFor(eventId, seatId))
                .toList();

        Long released = redis.execute(releaseScript, keys, token.toString());
        long count = released == null ? 0 : released;

        if (count < keys.size()) {
            // Worth a log line but not a warning: the usual cause is that the
            // TTL simply won the race, which is the system working as designed.
            log.debug("Released {}/{} holds for event {} (rest had already expired)",
                    count, keys.size(), eventId);
        }
        return count;
    }

    /**
     * Push the expiry out, only if this token still owns every seat.
     *
     * @return {@code true} if extended. {@code false} means at least one seat has
     *         been lost, and the caller must treat the entire hold as dead -
     *         extending a partial set would keep seats out of sale for a booking
     *         that can no longer be completed.
     */
    public boolean extend(long eventId, Collection<Long> eventSeatIds, UUID token, Duration newTtl) {
        if (eventSeatIds == null || eventSeatIds.isEmpty()) {
            return false;
        }
        List<String> keys = eventSeatIds.stream()
                .distinct().sorted()
                .map(seatId -> keyFor(eventId, seatId))
                .toList();

        Long result = redis.execute(extendScript, keys, token.toString(), String.valueOf(newTtl.toMillis()));
        // The script answers -1 when ownership was lost, otherwise the count.
        return result != null && result > 0;
    }

    /**
     * Which of these seats are held by <em>somebody</em> right now.
     *
     * <p>Used by the seat-map endpoint so the UI can grey out seats another user
     * is in the middle of buying. Note the result is advisory and already
     * slightly stale by the time it reaches the browser - that is fine. It exists
     * to reduce disappointment, not to enforce anything.
     *
     * <p>One {@code MGET} for the whole auditorium: ~200 keys, one round trip.
     * The alternative - a {@code GET} per seat - would be 200 round trips and
     * turn a 2ms call into 200ms.
     */
    public Set<Long> findHeldSeatIds(long eventId, Collection<Long> candidateSeatIds) {
        if (candidateSeatIds == null || candidateSeatIds.isEmpty()) {
            return Set.of();
        }
        List<Long> ordered = List.copyOf(candidateSeatIds);
        List<String> keys = ordered.stream().map(id -> keyFor(eventId, id)).toList();

        List<String> values = redis.opsForValue().multiGet(keys);
        if (values == null) {
            return Set.of();
        }

        Set<Long> held = new LinkedHashSet<>();
        for (int i = 0; i < values.size(); i++) {
            // multiGet preserves position and returns null for missing keys.
            if (values.get(i) != null) {
                held.add(ordered.get(i));
            }
        }
        return held;
    }

    /**
     * Whether {@code token} still holds every one of these seats.
     *
     * <p>Called at the top of confirmation. Getting a false here means the hold
     * lapsed while the user was paying, and the booking must be refused rather
     * than quietly completed - the seats may already belong to someone else.
     */
    public boolean ownsAll(long eventId, Collection<Long> eventSeatIds, UUID token) {
        if (eventSeatIds == null || eventSeatIds.isEmpty()) {
            return false;
        }
        List<String> keys = eventSeatIds.stream().map(id -> keyFor(eventId, id)).toList();
        List<String> values = redis.opsForValue().multiGet(keys);
        if (values == null || values.size() != keys.size()) {
            return false;
        }
        String expected = token.toString();
        for (String v : values) {
            if (!expected.equals(v)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Remaining life of a hold, from Redis itself.
     *
     * <p>We also store {@code expires_at} on the booking row, but Redis is the
     * authority - the row is a convenience so the checkout page can render a
     * countdown after a refresh without a Redis round trip. When the two
     * disagree, Redis is right, because Redis is what actually releases the seat.
     */
    public Optional<Duration> timeToLive(long eventId, long anyEventSeatId) {
        Long millis = redis.getExpire(keyFor(eventId, anyEventSeatId), java.util.concurrent.TimeUnit.MILLISECONDS);
        // Spring returns -2 for "no such key" and -1 for "key exists, no TTL".
        // Neither is a duration, and silently turning -2 into "expires in -2ms"
        // is exactly the kind of bug that only shows up in production.
        if (millis == null || millis < 0) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofMillis(millis));
    }
}
