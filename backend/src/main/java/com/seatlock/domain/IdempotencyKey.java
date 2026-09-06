package com.seatlock.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A record that "this user already sent this exact request, and here is what we
 * answered".
 *
 * <h2>The problem</h2>
 *
 * A user taps Pay on a train. The request reaches us, we create the booking, and
 * the response is lost somewhere in a tunnel. Their phone retries. Without
 * protection they now hold two bookings for the same seats - except they cannot,
 * because the seats are gone, so instead they get a confusing 409 for a booking
 * that actually succeeded. Either way the experience is broken.
 *
 * <h2>The protocol</h2>
 *
 * The client generates a UUID per user-intent (not per HTTP attempt) and sends
 * it as {@code Idempotency-Key}. On arrival we try to INSERT the key.
 *
 * <ul>
 *   <li><b>INSERT succeeds</b> - first time we have seen it. Execute the
 *       booking, then write the response back onto this row.</li>
 *   <li><b>INSERT violates the unique constraint</b> - a duplicate. Load the
 *       existing row and replay its stored response verbatim.</li>
 * </ul>
 *
 * <p>The race between two simultaneous retries is resolved by the database, not
 * by application logic: {@code UNIQUE (user_id, idem_key)} means exactly one
 * INSERT can win, and the loser gets a constraint violation it can recognise.
 * Doing this with "SELECT, then INSERT if absent" would have a window between
 * the two statements where both requests see nothing and both proceed.
 *
 * <h2>Why store the request hash</h2>
 *
 * A key is a promise that the <em>same</em> request is being retried. If a client
 * reuses a key with a different body - by bug or on purpose - replaying the old
 * response would be wrong and potentially a way to probe what another request
 * returned. Comparing a SHA-256 of the canonical body lets us answer 422 instead.
 *
 * <h2>Why scope it per user</h2>
 *
 * Keys are client-chosen strings. If they were globally unique, a malicious
 * client could claim the key {@code "1"} and either block another user's request
 * or read back their stored response. Scoping to {@code user_id} makes one
 * user's key namespace unreachable from another account.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "idem_key", nullable = false, length = 120, updatable = false)
    private String idemKey;

    @Column(nullable = false, length = 120, updatable = false)
    private String endpoint;

    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    @Column(name = "response_status")
    private Short responseStatus;

    @Column(name = "response_body", columnDefinition = "text")
    private String responseBody;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id")
    private Booking booking;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected IdempotencyKey() { }

    public IdempotencyKey(User user, String idemKey, String endpoint, String requestHash) {
        this.user = user;
        this.idemKey = idemKey;
        this.endpoint = endpoint;
        this.requestHash = requestHash;
    }

    /** True once the original request finished and we have something to replay. */
    public boolean hasStoredResponse() {
        return responseStatus != null && responseBody != null;
    }

    public void storeResponse(int status, String body, Booking booking) {
        this.responseStatus = (short) status;
        this.responseBody = body;
        this.booking = booking;
    }

    public Long getId() { return id; }
    public String getIdemKey() { return idemKey; }
    public String getEndpoint() { return endpoint; }
    public String getRequestHash() { return requestHash; }
    public Short getResponseStatus() { return responseStatus; }
    public String getResponseBody() { return responseBody; }
    public Booking getBooking() { return booking; }
    public Instant getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IdempotencyKey other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() { return IdempotencyKey.class.hashCode(); }
}
