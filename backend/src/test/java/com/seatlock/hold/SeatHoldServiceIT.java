package com.seatlock.hold;

import com.seatlock.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hold layer on its own, against a real Redis running the real Lua scripts.
 *
 * <h2>Why there is no database in sight</h2>
 *
 * Every assertion here is about Redis semantics: atomicity, TTLs, and above all
 * <b>ownership</b>. A hold key is just a string keyed by {@code event_seats.id},
 * so the ids used below need not exist in Postgres for any of it to be
 * meaningful - and not creating them keeps each test to a handful of
 * milliseconds. {@link com.seatlock.booking.BookingLifecycleIT} covers the
 * points where the two stores have to agree.
 *
 * <p>This class lives in {@code com.seatlock.hold} deliberately: it needs
 * {@link SeatHoldService#keyFor}, which is package-private because nothing
 * outside this package has any business knowing the key layout. A test that has
 * to reach into the key format is exactly the case that justifies
 * package-private rather than {@code public}.
 */
@DisplayName("Seat holds: Redis lease semantics")
class SeatHoldServiceIT extends IntegrationTestBase {

    /**
     * An arbitrary event id. Nothing reads it back out of Postgres; it only
     * shapes the Redis key's hash tag.
     */
    private static final long EVENT = 4242L;

    private static final long SEAT_A = 1L;
    private static final long SEAT_B = 2L;
    private static final long SEAT_C = 3L;
    private static final long SEAT_D = 4L;

    private static final Duration EIGHT_MINUTES = Duration.ofMinutes(8);

    @Autowired private SeatHoldService holds;
    @Autowired private StringRedisTemplate redis;

    @BeforeEach
    void flushRedis() {
        // Holds survive anything a test does to Postgres, because Redis has no
        // transaction to roll back. A leftover key would make the next test look
        // like it lost a race to a ghost.
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    // ==================================================================
    // acquire
    // ==================================================================

    @Test
    @DisplayName("acquire reserves every requested seat and gives the lease a TTL")
    void acquireReservesEverySeatWithATtl() {
        UUID token = UUID.randomUUID();

        HoldResult result = holds.acquire(EVENT, List.of(SEAT_C, SEAT_A, SEAT_B), token, EIGHT_MINUTES);

        assertThat(result.acquired()).isTrue();
        assertThat(result.conflictingSeatIds()).isEmpty();
        // Canonical order, not request order: the service sorts before it locks,
        // so that two callers asking for the same seats in different orders can
        // never build a lock-ordering cycle between them.
        assertThat(result.heldSeatIds()).containsExactly(SEAT_A, SEAT_B, SEAT_C);

        // The TTL is the whole safety net. If SET ever ran without PX, an
        // abandoned checkout would take a seat off sale permanently and nothing
        // in the application would ever notice - so this assertion is not
        // pedantry, it is the difference between a lease and a leak.
        Duration ttl = holds.timeToLive(EVENT, SEAT_A).orElseThrow();
        assertThat(ttl).isBetween(EIGHT_MINUTES.minusSeconds(20), EIGHT_MINUTES);
    }

    @Test
    @DisplayName("acquire is all-or-nothing and names exactly the seat that was lost")
    void acquireIsAllOrNothing() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        // Alice takes one seat out of the middle of Bob's intended block.
        assertThat(holds.acquire(EVENT, List.of(SEAT_C), alice, EIGHT_MINUTES).acquired()).isTrue();

        HoldResult bobsAttempt = holds.acquire(EVENT, List.of(SEAT_A, SEAT_B, SEAT_C, SEAT_D),
                bob, EIGHT_MINUTES);

        assertThat(bobsAttempt.acquired()).isFalse();
        assertThat(bobsAttempt.heldSeatIds()).isEmpty();
        assertThat(bobsAttempt.conflictingSeatIds())
                .as("the caller is told precisely which seat it lost, not just that it failed")
                .containsExactly(SEAT_C);

        // The part that actually matters. A partial reservation would take three
        // seats off sale for a booking that can never complete, and the user
        // would be told "you got 3 of your 4 seats" - which is not a success.
        assertThat(redis.keys("seatlock:hold:*"))
                .as("only Alice's single key may exist")
                .hasSize(1);
        assertThat(holds.ownsAll(EVENT, List.of(SEAT_A), bob)).isFalse();
        assertThat(holds.ownsAll(EVENT, List.of(SEAT_B), bob)).isFalse();
        assertThat(holds.ownsAll(EVENT, List.of(SEAT_D), bob)).isFalse();
    }

    @Test
    @DisplayName("re-acquiring with the same token succeeds and refreshes the TTL")
    void sameTokenReacquiresAndRefreshesTheTtl() {
        UUID token = UUID.randomUUID();

        assertThat(holds.acquire(EVENT, List.of(SEAT_A, SEAT_B), token, Duration.ofSeconds(60))
                .acquired()).isTrue();
        assertThat(holds.timeToLive(EVENT, SEAT_A).orElseThrow())
                .isLessThanOrEqualTo(Duration.ofSeconds(60));

        // A client whose request timed out on the network retries. It must not
        // be treated as a competitor for its own seats: the Lua script compares
        // the stored value against the token rather than relying on SET NX, so
        // "already ours" is a success that also pushes the lease out.
        HoldResult retry = holds.acquire(EVENT, List.of(SEAT_A, SEAT_B), token, EIGHT_MINUTES);

        assertThat(retry.acquired()).isTrue();
        assertThat(retry.conflictingSeatIds()).isEmpty();
        assertThat(holds.timeToLive(EVENT, SEAT_A).orElseThrow())
                .as("the retry refreshed the lease rather than colliding with it")
                .isGreaterThan(Duration.ofSeconds(60));
    }

    // ==================================================================
    // release - the fencing token
    // ==================================================================

    /**
     * <h2>The most important test in this class.</h2>
     *
     * This is the classic distributed-lock bug, and it is the one that gets
     * written wrong far more often than it gets written right. The failure has
     * nothing to do with Redis being unreliable; it happens on a perfectly
     * healthy Redis, and it is entirely a consequence of a lease that can expire
     * while its owner still believes they hold it.
     *
     * <pre>
     *   t=0     Alice holds seat A5. Lease: 8 minutes.
     *   t=8m    The lease elapses. Redis deletes the key by itself. A5 is free.
     *   t=8m+1s Bob acquires A5. The key now stores BOB's token.
     *   t=8m+2s Alice's "cancel my hold" request - which had been stuck behind a
     *           stalled TCP connection for the last two minutes - finally lands.
     * </pre>
     *
     * <p>With a plain {@code DEL}, that last step deletes <b>Bob's</b> hold.
     * Carol then acquires A5 a second later, and Bob and Carol both believe the
     * seat is theirs. Alice released a lock she no longer owned, and neither she
     * nor the server ever did anything obviously wrong.
     *
     * <p>The token is what closes it. It is a <b>fencing value</b>: the release
     * script does a compare-and-delete inside Lua, atomically, so a stale owner
     * can only ever delete a key that still carries their own token. Doing the
     * comparison in Java would reintroduce the bug in miniature - the lease can
     * expire between the {@code GET} and the {@code DEL}.
     *
     * <p>Deleting the key by hand below stands in for the TTL firing. It
     * produces exactly the state an expiry produces, without an eight-minute
     * test.
     */
    @Test
    @DisplayName("a stale token cannot release a hold that now belongs to somebody else")
    void staleTokenCannotReleaseSomebodyElsesHold() {
        UUID aliceToken = UUID.randomUUID();
        UUID bobToken = UUID.randomUUID();

        assertThat(holds.acquire(EVENT, List.of(SEAT_A), aliceToken, EIGHT_MINUTES).acquired()).isTrue();

        // ---- The lease expires. Redis would do this on its own after the TTL.
        redis.delete(SeatHoldService.keyFor(EVENT, SEAT_A));

        // ---- Bob, quite legitimately, takes the now-free seat.
        assertThat(holds.acquire(EVENT, List.of(SEAT_A), bobToken, EIGHT_MINUTES).acquired()).isTrue();

        // ---- Alice's long-delayed release finally arrives.
        long released = holds.release(EVENT, List.of(SEAT_A), aliceToken);

        assertThat(released)
                .as("Alice deletes nothing: the key no longer carries her token")
                .isZero();
        assertThat(holds.ownsAll(EVENT, List.of(SEAT_A), bobToken))
                .as("Bob's hold must survive a stale release from a previous owner")
                .isTrue();
        assertThat(redis.opsForValue().get(SeatHoldService.keyFor(EVENT, SEAT_A)))
                .isEqualTo(bobToken.toString());
    }

    @Test
    @DisplayName("release is idempotent: twice, or on an already-expired hold, is safe")
    void releaseIsIdempotent() {
        UUID token = UUID.randomUUID();
        holds.acquire(EVENT, List.of(SEAT_A, SEAT_B), token, EIGHT_MINUTES);

        assertThat(holds.release(EVENT, List.of(SEAT_A, SEAT_B), token))
                .as("both keys are handed back")
                .isEqualTo(2);

        // Calling again must be a no-op rather than an error. This matters
        // because release() is the compensating action in a catch block, and a
        // cleanup path that can itself throw is not much of a cleanup path -
        // it would replace a recoverable failure with an unrecoverable one.
        assertThat(holds.release(EVENT, List.of(SEAT_A, SEAT_B), token))
                .as("nothing left to release, and that is not an error")
                .isZero();

        // Same story for a hold nobody ever took: "fewer released than asked
        // for" is the normal reading of "the TTL got there first".
        assertThat(holds.release(EVENT, List.of(SEAT_C), UUID.randomUUID())).isZero();

        assertThat(redis.keys("seatlock:hold:*")).isEmpty();
    }

    // ==================================================================
    // extend
    // ==================================================================

    @Test
    @DisplayName("extend pushes the lease out while every seat is still owned")
    void extendSucceedsWhileEverySeatIsOwned() {
        UUID token = UUID.randomUUID();
        holds.acquire(EVENT, List.of(SEAT_A, SEAT_B, SEAT_C), token, Duration.ofSeconds(30));

        boolean extended = holds.extend(EVENT, List.of(SEAT_A, SEAT_B, SEAT_C), token, Duration.ofMinutes(3));

        assertThat(extended).isTrue();
        for (long seat : new long[]{SEAT_A, SEAT_B, SEAT_C}) {
            assertThat(holds.timeToLive(EVENT, seat).orElseThrow())
                    .as("seat %d should now have roughly three minutes left", seat)
                    .isGreaterThan(Duration.ofSeconds(150));
        }
    }

    @Test
    @DisplayName("extend refuses - and changes nothing - once even one seat has been lost")
    void extendFailsAndLeavesTheOtherSeatsUntouched() {
        UUID token = UUID.randomUUID();
        holds.acquire(EVENT, List.of(SEAT_A, SEAT_B, SEAT_C), token, Duration.ofSeconds(60));

        // One seat's lease elapses while the user is still on the payment page.
        redis.delete(SeatHoldService.keyFor(EVENT, SEAT_B));

        boolean extended = holds.extend(EVENT, List.of(SEAT_A, SEAT_B, SEAT_C), token, Duration.ofMinutes(10));

        assertThat(extended)
                .as("a hold covers seats the user intends to buy together; losing one kills it")
                .isFalse();

        // And crucially the script's first pass bailed out before writing
        // anything. Extending the survivors would keep seats out of sale for a
        // booking that can no longer complete - actively worse than failing.
        assertThat(holds.timeToLive(EVENT, SEAT_A).orElseThrow())
                .as("seat A must NOT have been extended")
                .isLessThanOrEqualTo(Duration.ofSeconds(60));
        assertThat(holds.timeToLive(EVENT, SEAT_C).orElseThrow())
                .as("seat C must NOT have been extended")
                .isLessThanOrEqualTo(Duration.ofSeconds(60));
    }

    // ==================================================================
    // read-only queries
    // ==================================================================

    @Test
    @DisplayName("findHeldSeatIds returns exactly the held subset of the candidates")
    void findHeldSeatIdsReturnsTheHeldSubset() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        holds.acquire(EVENT, List.of(SEAT_A), alice, EIGHT_MINUTES);
        holds.acquire(EVENT, List.of(SEAT_C), bob, EIGHT_MINUTES);

        Set<Long> held = holds.findHeldSeatIds(EVENT, List.of(SEAT_A, SEAT_B, SEAT_C, SEAT_D));

        // Held by *somebody* - this feeds the seat map, which greys out seats
        // another user is in the middle of buying regardless of who they are.
        assertThat(held).containsExactlyInAnyOrder(SEAT_A, SEAT_C);

        assertThat(holds.findHeldSeatIds(EVENT, List.of()))
                .as("an empty candidate list must not turn into an MGET with no keys")
                .isEmpty();
    }

    @Test
    @DisplayName("ownsAll is true only while every single seat is still ours")
    void ownsAllIsTrueOnlyWhenEverySeatIsStillOurs() {
        UUID token = UUID.randomUUID();
        UUID somebodyElse = UUID.randomUUID();
        holds.acquire(EVENT, List.of(SEAT_A, SEAT_B), token, EIGHT_MINUTES);

        assertThat(holds.ownsAll(EVENT, List.of(SEAT_A, SEAT_B), token)).isTrue();
        assertThat(holds.ownsAll(EVENT, List.of(SEAT_A, SEAT_B), somebodyElse)).isFalse();

        // A seat we never held is not ours, even though the other one is. This
        // is what the confirmation path calls, and answering "true" on a partial
        // match would let a booking complete over seats that belong to someone
        // else - the money would be taken before the database refused.
        assertThat(holds.ownsAll(EVENT, List.of(SEAT_A, SEAT_D), token)).isFalse();

        // A lapsed lease is indistinguishable from never having held it.
        redis.delete(SeatHoldService.keyFor(EVENT, SEAT_B));
        assertThat(holds.ownsAll(EVENT, List.of(SEAT_A, SEAT_B), token)).isFalse();
        assertThat(holds.ownsAll(EVENT, List.of(SEAT_A), token)).isTrue();
    }

    @Test
    @DisplayName("timeToLive reports empty for a key that does not exist")
    void timeToLiveIsEmptyForAnUnheldSeat() {
        // Spring returns -2 for "no such key". Turning that into "expires in
        // -2ms" is the sort of thing that only surfaces in production, so the
        // service maps every non-positive answer to an empty Optional.
        assertThat(holds.timeToLive(EVENT, SEAT_D)).isEmpty();
    }
}
