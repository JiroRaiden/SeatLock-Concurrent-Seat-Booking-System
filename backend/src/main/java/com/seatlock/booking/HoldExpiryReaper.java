package com.seatlock.booking;

import com.seatlock.domain.Booking;
import com.seatlock.domain.BookingStatus;
import com.seatlock.repository.BookingRepository;
import com.seatlock.repository.IdempotencyKeyRepository;
import com.seatlock.repository.RefreshTokenRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Background housekeeping: expire abandoned bookings, drop stale rows.
 *
 * <h2>What this job is, and what it is not</h2>
 *
 * It is <b>not</b> what releases seats. Redis releases seats, on its own, the
 * instant a TTL elapses, whether or not this application is running. That is the
 * entire reason holds live in Redis.
 *
 * <p>What this job does is tidy the <em>database</em>: turn PENDING bookings
 * whose hold has evaporated into EXPIRED ones, so that "my bookings" does not
 * show a phantom reservation and so the {@code booking_seats} rows stop claiming
 * the seat through the partial unique index.
 *
 * <p>The distinction matters when someone asks what happens if this job dies.
 * The answer is: seats still get released on time, users can still book, and the
 * only symptom is some stale PENDING rows. Compare that with a design where
 * holds are a database status - there, this job dying means seats stay locked
 * forever. Choosing which component can fail harmlessly is most of what
 * designing for reliability means.
 *
 * <h2>Running more than one instance</h2>
 *
 * Every instance runs this job, so with three instances the work is done three
 * times. Here that is harmless - {@code expireBooking} re-checks the status
 * inside its own transaction, so the second and third attempts find nothing to
 * do and the optimistic lock settles any genuine tie.
 *
 * <p>It is wasteful rather than wrong. The standard fix is ShedLock, which uses
 * a database row as a mutex so exactly one instance runs each scheduled task.
 * Not added here because the waste is a few queries a minute against an index
 * built for exactly this query - but knowing the name of the fix, and why you
 * did not need it yet, is the point.
 */
@Component
public class HoldExpiryReaper {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryReaper.class);

    /**
     * How many bookings one sweep handles.
     *
     * <p>Bounded deliberately. If the service were down for an hour there could
     * be tens of thousands of expired holds; loading them all would spike memory
     * and hold one very long transaction open, which in Postgres also blocks
     * autovacuum from cleaning up rows newer than that transaction's snapshot.
     * Small batches, run often, keep every transaction short.
     */
    private static final int BATCH_SIZE = 200;

    /** Idempotency keys stop being useful once no client could still be retrying. */
    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofDays(1);

    private final BookingRepository bookings;
    private final BookingPersistence persistence;
    private final RefreshTokenRepository refreshTokens;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final Counter expiredCounter;

    public HoldExpiryReaper(BookingRepository bookings,
                            BookingPersistence persistence,
                            RefreshTokenRepository refreshTokens,
                            IdempotencyKeyRepository idempotencyKeys,
                            MeterRegistry meterRegistry) {
        this.bookings = bookings;
        this.persistence = persistence;
        this.refreshTokens = refreshTokens;
        this.idempotencyKeys = idempotencyKeys;
        // A metric, not just a log line. "How many holds are being abandoned?"
        // is the number that tells you whether the 8 minute TTL is right, and
        // you cannot answer it by grepping logs after the fact.
        this.expiredCounter = Counter.builder("seatlock.holds.expired")
                .description("Pending bookings expired because their hold lapsed")
                .register(meterRegistry);
    }

    /**
     * {@code fixedDelay}, not {@code fixedRate}.
     *
     * <p>fixedRate schedules the next run a fixed interval after the previous
     * one <em>started</em>. If a sweep ever takes longer than the interval, runs
     * pile up on top of each other and the situation gets worse exactly when it
     * is already bad. fixedDelay measures from when the previous run
     * <em>finished</em>, so a slow sweep simply pushes the next one back.
     */
    @Scheduled(fixedDelayString = "${seatlock.reaper.fixed-delay:60s}")
    public void expireLapsedHolds() {
        Instant now = Instant.now();

        List<Booking> batch = bookings.findExpiredBatch(
                BookingStatus.PENDING, now, PageRequest.of(0, BATCH_SIZE));

        if (batch.isEmpty()) {
            return;
        }

        int expired = 0;
        for (Booking booking : batch) {
            try {
                // Each in its own REQUIRES_NEW transaction, so one failure -
                // typically an optimistic lock conflict with a user confirming
                // at that exact instant - does not roll back the rest of the
                // batch. Losing a race here is the correct outcome: the user
                // paid, so their booking must win over our cleanup.
                persistence.expireBooking(booking.getId(), now);
                expired++;
            } catch (RuntimeException ex) {
                log.debug("Could not expire booking {} this pass: {}",
                        booking.getId(), ex.getClass().getSimpleName());
            }
        }

        expiredCounter.increment(expired);
        log.info("Reaper expired {} lapsed hold(s)", expired);
    }

    /**
     * Nightly cleanup of rows that have outlived their purpose.
     *
     * <p>Cron rather than a fixed delay because this should happen at a quiet
     * hour, not at a random offset from whenever the process last restarted.
     */
    @Scheduled(cron = "${seatlock.reaper.cleanup-cron:0 15 3 * * *}")
    @Transactional
    public void cleanUpStaleRows() {
        Instant now = Instant.now();

        int tokens = refreshTokens.deleteExpiredBefore(now.minus(Duration.ofDays(30)));
        int keys = idempotencyKeys.deleteOlderThan(now.minus(IDEMPOTENCY_RETENTION));

        if (tokens > 0 || keys > 0) {
            log.info("Nightly cleanup removed {} expired refresh token(s) and {} idempotency key(s)",
                    tokens, keys);
        }
    }
}
