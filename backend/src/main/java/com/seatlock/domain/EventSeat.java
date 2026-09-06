package com.seatlock.domain;

import jakarta.persistence.*;

/**
 * One seat, for one event. The bookable unit of the whole system.
 *
 * <p>Seat "A5" at a venue is a single row in {@code seats}. But A5 for tonight's
 * 7pm show and A5 for tomorrow's 10am show are two independent things that must
 * be sellable independently - so each becomes its own {@code event_seats} row.
 *
 * <h2>The {@code @Version} field</h2>
 *
 * This one annotation is the second of our three defences against overselling.
 * With it, Hibernate rewrites every UPDATE to this table:
 *
 * <pre>
 *   UPDATE event_seats
 *      SET status = 'BOOKED', version = 4
 *    WHERE id = 91
 *      AND version = 3;          &lt;-- Hibernate adds this
 * </pre>
 *
 * Then it checks the returned row count. If two transactions both read version 3
 * and both try to book:
 *
 * <pre>
 *   T1: UPDATE ... WHERE id=91 AND version=3   -> 1 row  -> commits, version is now 4
 *   T2: UPDATE ... WHERE id=91 AND version=3   -> 0 rows -> OptimisticLockException
 * </pre>
 *
 * <p>T2 loses cleanly and we turn that exception into a 409 Conflict. Crucially,
 * <b>no lock is ever held</b>. Nobody waits, nothing deadlocks, and a slow client
 * cannot block anyone else. Compare with {@code SELECT ... FOR UPDATE}, which
 * would make T2 sit and wait for T1's transaction to end.
 *
 * <h2>Why is this needed at all, if Redis already gated the seat?</h2>
 *
 * Because Redis is a separate machine that can fail, fail over, or be restarted
 * with an empty dataset. In any of those windows two requests can both believe
 * they hold seat A5. Redis makes conflicts <em>rare</em>; {@code @Version} makes
 * the rare case <em>correct</em>. Fast path and safe path are different
 * mechanisms on purpose - a single mechanism that is both would have to be slow.
 */
@Entity
@Table(name = "event_seats")
public class EventSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * {@code LAZY} on every {@code @ToOne}. The JPA default for ToOne is EAGER,
     * which means loading 200 seats for a seat map would silently fire 200 extra
     * queries for events we already have in hand. Marking these lazy and then
     * fetching deliberately (with a JOIN FETCH or a projection) is how you keep
     * control of what the database is asked for.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "price_tier_id", nullable = false)
    private PriceTier priceTier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SeatStatus status = SeatStatus.AVAILABLE;

    /**
     * The optimistic lock counter. Managed entirely by Hibernate: never set it,
     * never increment it by hand, and never expose it in a request body where a
     * client could choose its value.
     */
    @Version
    @Column(nullable = false)
    private Long version;

    protected EventSeat() { }

    public EventSeat(Event event, Seat seat, PriceTier priceTier) {
        this.event = event;
        this.seat = seat;
        this.priceTier = priceTier;
        this.status = SeatStatus.AVAILABLE;
    }

    /**
     * Move the seat to BOOKED.
     *
     * <p>The guard is here, on the entity, rather than in the service. Putting
     * the rule next to the data it protects means every path that books a seat
     * goes through the same check - there is no second code path that forgot it.
     *
     * @throws IllegalStateException if the seat was not available. The caller
     *         translates this into a 409; it should never surface as a 500.
     */
    public void markBooked() {
        if (this.status != SeatStatus.AVAILABLE) {
            throw new IllegalStateException(
                    "Seat " + id + " cannot be booked from status " + status);
        }
        this.status = SeatStatus.BOOKED;
    }

    /** Return a sold seat to sale, on cancellation or expiry. */
    public void release() {
        if (this.status == SeatStatus.BOOKED) {
            this.status = SeatStatus.AVAILABLE;
        }
    }

    public boolean isAvailable() {
        return status == SeatStatus.AVAILABLE;
    }

    public Long getId() { return id; }
    public Event getEvent() { return event; }
    public Seat getSeat() { return seat; }
    public PriceTier getPriceTier() { return priceTier; }
    public SeatStatus getStatus() { return status; }
    public Long getVersion() { return version; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EventSeat other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() { return EventSeat.class.hashCode(); }
}
