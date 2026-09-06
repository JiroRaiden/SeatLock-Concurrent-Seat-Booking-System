package com.seatlock.domain;

import jakarta.persistence.*;

/**
 * One seat line inside a booking.
 *
 * <p>This table carries the strongest oversell guarantee in the system, and it
 * is not written in Java. The migration creates:
 *
 * <pre>
 *   CREATE UNIQUE INDEX booking_seats_one_active_per_seat
 *       ON booking_seats (event_seat_id) WHERE active;
 * </pre>
 *
 * <p>A partial unique index: uniqueness applies only to rows where
 * {@code active} is true. So a seat may appear in many historical bookings
 * (cancelled, expired) but in at most <b>one</b> live booking, and Postgres will
 * reject the second INSERT with a constraint violation regardless of what the
 * application layer believes.
 *
 * <p>That is the difference between "we check carefully" and "it cannot happen".
 * Redis can be flushed and the {@code @Version} check can be bypassed by a
 * native query written by a future contributor in a hurry; this index cannot be
 * bypassed by any code path at all.
 */
@Entity
@Table(name = "booking_seats")
public class BookingSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_seat_id", nullable = false)
    private EventSeat eventSeat;

    /**
     * The price at the moment of sale, copied rather than referenced.
     *
     * <p>If the venue re-prices the Prime tier next week, a ticket already issued
     * must still show what its buyer actually paid. Joining to the live tier
     * price would silently rewrite history on every receipt ever printed.
     */
    @Column(name = "price_minor", nullable = false)
    private Long priceMinor;

    /**
     * Whether this line still claims the seat. Set false when the booking is
     * cancelled or expires - which is what drops the row out of the partial
     * unique index and returns the seat to sale.
     *
     * <p>A database trigger keeps this in step with {@code bookings.status}, so
     * even a manual UPDATE against the bookings table cannot leave a cancelled
     * booking holding a live seat claim.
     */
    @Column(nullable = false)
    private boolean active = true;

    protected BookingSeat() { }

    BookingSeat(Booking booking, EventSeat eventSeat, Long priceMinor) {
        this.booking = booking;
        this.eventSeat = eventSeat;
        this.priceMinor = priceMinor;
        this.active = true;
    }

    public void deactivate() { this.active = false; }

    public Long getId() { return id; }
    public Booking getBooking() { return booking; }
    public EventSeat getEventSeat() { return eventSeat; }
    public Long getPriceMinor() { return priceMinor; }
    public boolean isActive() { return active; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BookingSeat other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() { return BookingSeat.class.hashCode(); }
}
