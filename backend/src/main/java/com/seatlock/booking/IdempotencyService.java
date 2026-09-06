package com.seatlock.booking;

import com.seatlock.domain.Booking;
import com.seatlock.domain.IdempotencyKey;
import com.seatlock.domain.User;
import com.seatlock.repository.IdempotencyKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

/**
 * Makes {@code POST /bookings/{id}/confirm} safe to retry.
 *
 * <h2>The race, and why the database resolves it</h2>
 *
 * The naive implementation is:
 *
 * <pre>
 *   if (repo.findByKey(k).isEmpty()) {     // (1)
 *       doTheWork();
 *       repo.save(new Key(k, response));   // (2)
 *   }
 * </pre>
 *
 * <p>Two simultaneous retries both run (1), both see nothing, and both do the
 * work. The check-then-act gap is the bug, and no amount of care in Java closes
 * it - the two requests may be on different servers entirely.
 *
 * <p>So we invert it: <b>write first, and let the unique constraint decide.</b>
 * Both requests try to INSERT. Exactly one can succeed, because
 * {@code UNIQUE (user_id, idem_key)} is enforced by Postgres at the row level.
 * The loser gets a constraint violation, which is not a failure but an answer:
 * "somebody else is already handling this".
 *
 * <p>This is the same shape as the seat-booking guarantee elsewhere in this
 * project. When two actors must not both proceed, the cheapest correct referee
 * is usually a unique index.
 *
 * <h2>Why every method here is {@code REQUIRES_NEW}</h2>
 *
 * The idempotency record must survive independently of the work it protects.
 * If the key were written in the same transaction as the booking, then a booking
 * that rolls back would roll back its key too - and the retry would find nothing
 * and do the work again. Its whole job is to outlive the failure.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyKeyRepository repository;

    public IdempotencyService(IdempotencyKeyRepository repository) {
        this.repository = repository;
    }

    /** What the caller should do next. */
    public enum Outcome {
        /** Nobody has used this key. Proceed with the work. */
        PROCEED,
        /** Already completed. Return the stored response verbatim. */
        REPLAY,
        /** Claimed but not finished - a concurrent duplicate is in flight. */
        IN_FLIGHT,
        /** Same key, different request body. Refuse. */
        MISMATCH
    }

    public record Claim(Outcome outcome, Long recordId, Short storedStatus, String storedBody) {
        static Claim proceed(Long id) { return new Claim(Outcome.PROCEED, id, null, null); }
        static Claim replay(IdempotencyKey k) {
            return new Claim(Outcome.REPLAY, k.getId(), k.getResponseStatus(), k.getResponseBody());
        }
        static Claim inFlight(Long id) { return new Claim(Outcome.IN_FLIGHT, id, null, null); }
        static Claim mismatch() { return new Claim(Outcome.MISMATCH, null, null, null); }
    }

    /**
     * Stake a claim on this key, or discover what happened to it last time.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Claim claim(User user, String key, String endpoint, String requestBody) {
        String requestHash = sha256(requestBody == null ? "" : requestBody);

        try {
            IdempotencyKey record = new IdempotencyKey(user, key, endpoint, requestHash);
            repository.saveAndFlush(record);
            // The flush is what makes this work. Without it Hibernate would
            // defer the INSERT to the end of the transaction, and we would leave
            // this method believing we had won a race that has not been run yet.
            return Claim.proceed(record.getId());

        } catch (DataIntegrityViolationException ex) {
            // Somebody got here first - either a completed earlier request or a
            // concurrent duplicate.
            Optional<IdempotencyKey> existing = repository.findByUserAndKey(user.getId(), key);
            if (existing.isEmpty()) {
                // The unique constraint fired but we cannot find the row. That
                // means the winner's transaction has not committed yet, so the
                // row is invisible to us under READ COMMITTED. Treat as in-flight.
                return Claim.inFlight(null);
            }

            IdempotencyKey record = existing.get();

            // A key is a promise that the SAME request is being retried. A
            // different body under the same key is either a client bug or an
            // attempt to fish for another request's stored response.
            if (!record.getRequestHash().equals(requestHash)) {
                log.warn("Idempotency key reused with a different payload by user id={}", user.getId());
                return Claim.mismatch();
            }

            if (record.hasStoredResponse()) {
                return Claim.replay(record);
            }
            return Claim.inFlight(record.getId());
        }
    }

    /**
     * Record what we answered, so a later retry can replay it.
     *
     * <p>Its own transaction again: this runs after the booking has committed,
     * and a failure to store the response must not undo the booking. Losing the
     * record is survivable (a retry would get a normal 409 instead of a clean
     * replay); losing the booking would not be.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void storeResponse(Long recordId, int status, String body, Booking booking) {
        if (recordId == null) {
            return;
        }
        repository.findById(recordId)
                  .ifPresent(record -> record.storeResponse(status, body, booking));
    }

    /**
     * Give up a claim we could not fulfil.
     *
     * <p>Called when the work failed for a reason the client could reasonably
     * retry - a declined payment, say. Keeping the key would mean the retry
     * replays "declined" forever, even after the customer fixes their card.
     *
     * <p>Note what we do NOT delete: keys whose work genuinely completed, and
     * keys whose work failed in a way that might have partially happened. Those
     * must stay, because retrying them is exactly what we are guarding against.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseClaim(Long recordId) {
        if (recordId != null) {
            repository.deleteById(recordId);
        }
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
