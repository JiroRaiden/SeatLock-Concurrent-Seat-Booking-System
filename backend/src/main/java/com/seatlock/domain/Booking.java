package com.seatlock.domain;

import jakarta.persistence.*;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A customer's claim on one or more seats for one event.
 */
@Entity
@Table(name = "bookings")
public class Booking {

    /**
     * Deliberately excludes I, O, 0 and 1 - characters people mis-read and
     * mis-dictate over the phone when reading a reference to support staff.
     */
    private static final char[] REFERENCE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    /**
     * {@link SecureRandom}, not {@link java.util.Random}. Booking references are
     * quoted at the counter to collect tickets, so a guessable reference is a
     * way to collect someone else's tickets. Random's internal state is
     * recoverable from a handful of outputs; SecureRandom's is not.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The identifier used in URLs. The auto-increment {@code id} never leaves
     * the server.
     *
     * <p>Two reasons. First, {@code /bookings/1837} invites someone to try 1836;
     * we do check ownership on that endpoint, but a random id means the probe is
     * not even worth writing. Second, sequential ids leak business volume - a
     * competitor can book twice a day and read your growth rate off the ids.
     */
    @Column(name = "public_id", nullable = false, updatable = false)
    private UUID publicId = UUID.randomUUID();

    /** Short human-quotable code, e.g. {@code SL-7QK4M2}. */
    @Column(nullable = false, length = 16, updatable = false)
    private String reference;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BookingStatus status = BookingStatus.PENDING;

    @Column(name = "total_minor", nullable = false)
    private Long totalMinor = 0L;

    /**
     * {@code cascade = ALL} plus {@code orphanRemoval} because booking_seats rows
     * have no life of their own - they exist only as part of a booking, and
     * deleting the booking must delete them.
     *
     * <p>{@code LAZY} so that listing 50 bookings does not drag in every seat of
     * every one of them.
     */
    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL,
               orphanRemoval = true, fetch = FetchType.LAZY)
    private List<BookingSeat> seats = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /**
     * When the underlying Redis hold lapses. Persisted even though Redis is the
     * real authority, so that a user who reloads the checkout page gets an
     * accurate countdown without us having to round-trip to Redis for it.
     */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    /**
     * Optimistic lock on the booking itself. Stops a double-tap on "Cancel" from
     * running the cancellation logic twice, and stops confirm and cancel from
     * interleaving.
     */
    @Version
    @Column(nullable = false)
    private Long version;

    protected Booking() { }

    /**
     * @param publicId the booking's public identifier, supplied by the caller
     *                 rather than generated here.
     *
     *                 <p>This looks odd until you see why: the same UUID is used
     *                 as the ownership token for the Redis seat hold, and the
     *                 hold has to be acquired <em>before</em> this row is
     *                 written. (Acquire first, then persist - so that a failure
     *                 to persist leaves a hold that expires by itself, rather
     *                 than a booking row with no reservation behind it.) The id
     *                 therefore has to exist before the entity does.
     *
     *                 <p>One identifier for both means there is no mapping table
     *                 between "the reservation" and "the pending booking", and
     *                 no way for the two to get out of step.
     */
    public Booking(User user, Event event, Instant expiresAt, UUID publicId) {
        this.user = user;
        this.event = event;
        this.expiresAt = expiresAt;
        this.publicId = publicId;
        this.status = BookingStatus.PENDING;
        this.reference = generateReference();
    }

    private static String generateReference() {
        // 6 chars from a 32-symbol alphabet = 32^6 ~= 1.07 billion combinations.
        // With a UNIQUE constraint backing it, a collision is a retryable
        // exception, not a correctness problem.
        StringBuilder sb = new StringBuilder("SL-");
        for (int i = 0; i < 6; i++) {
            sb.append(REFERENCE_ALPHABET[RANDOM.nextInt(REFERENCE_ALPHABET.length)]);
        }
        return sb.toString();
    }

    /**
     * Adds a seat line and keeps the running total in step.
     *
     * <p>The total is maintained here rather than recomputed on read so that the
     * amount charged and the amount displayed are one stored value, and cannot
     * drift if tier prices change later.
     */
    public void addSeat(EventSeat eventSeat, long priceMinor) {
        BookingSeat line = new BookingSeat(this, eventSeat, priceMinor);
        this.seats.add(line);
        this.totalMinor += priceMinor;
    }

    /**
     * PENDING -> CONFIRMED.
     *
     * @throws IllegalStateException if called on anything but a PENDING booking.
     *         A double-confirm is a bug or a replayed request, never a no-op we
     *         should quietly accept.
     */
    public void confirm(Instant now) {
        if (this.status != BookingStatus.PENDING) {
            throw new IllegalStateException("Cannot confirm a booking in status " + status);
        }
        this.status = BookingStatus.CONFIRMED;
        this.confirmedAt = now;
        this.expiresAt = null;   // a confirmed booking has no expiry
    }

    /**
     * PENDING or CONFIRMED -> CANCELLED.
     *
     * <p>The allowed source states are listed explicitly rather than expressed as
     * {@code !isTerminal()}. They are not the same set: {@link BookingStatus#isTerminal()}
     * counts CONFIRMED as terminal (it is a final, successful outcome and no
     * background job should touch it), but a customer may absolutely cancel a
     * booking they have already paid for. Writing the guard as
     * {@code !isTerminal()} made every cancellation of a paid booking throw, and
     * the global handler turned that into a 500 - a real bug that lived here
     * until the test suite went looking for it.
     *
     * <p>The lesson is worth keeping: a predicate named for one purpose
     * ("should a sweeper skip this?") is rarely the right predicate for another
     * ("may a user do this?"), however similar the two sets look.
     */
    public void cancel(Instant now) {
        if (this.status != BookingStatus.PENDING && this.status != BookingStatus.CONFIRMED) {
            throw new IllegalStateException("Cannot cancel a booking in status " + status);
        }
        this.status = BookingStatus.CANCELLED;
        this.cancelledAt = now;
    }

    /** Marked by the reaper when the hold lapsed without payment. */
    public void expire(Instant now) {
        if (this.status != BookingStatus.PENDING) {
            throw new IllegalStateException("Cannot expire a booking in status " + status);
        }
        this.status = BookingStatus.EXPIRED;
        this.cancelledAt = now;
    }

    /**
     * Push the displayed expiry out after Redis granted an extension.
     *
     * <p>Named awkwardly on purpose. A plain {@code setExpiresAt} would look
     * like an ordinary property setter and invite use from anywhere; this name
     * says there is exactly one caller and one reason. Redis remains the
     * authority on when a hold actually dies - this column only exists so the
     * checkout page can draw a countdown after a refresh without a Redis call.
     */
    public void setExpiresAtForExtension(Instant expiresAt) {
        if (this.status != BookingStatus.PENDING) {
            throw new IllegalStateException("Only a pending booking has an expiry");
        }
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(Instant now) {
        return status == BookingStatus.PENDING
                && expiresAt != null
                && !now.isBefore(expiresAt);
    }

    /** True if this booking belongs to the given user id. Used for authorization. */
    public boolean isOwnedBy(Long userId) {
        return user != null && user.getId() != null && user.getId().equals(userId);
    }

    public Long getId() { return id; }
    public UUID getPublicId() { return publicId; }
    public String getReference() { return reference; }
    public User getUser() { return user; }
    public Event getEvent() { return event; }
    public BookingStatus getStatus() { return status; }
    public Long getTotalMinor() { return totalMinor; }
    public List<BookingSeat> getSeats() { return seats; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public Long getVersion() { return version; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Booking other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() { return Booking.class.hashCode(); }
}
