package com.seatlock.domain;

import jakarta.persistence.*;

/**
 * A price band within one event: Recliner / Prime / Classic.
 *
 * <p>Tiers belong to the <em>event</em>, not the venue, because the same
 * auditorium charges differently for a Tuesday matinee and a Friday premiere.
 */
@Entity
@Table(name = "price_tiers")
public class PriceTier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(nullable = false, length = 40)
    private String name;

    /**
     * Price in the currency's minor unit - paise, not rupees.
     *
     * <p>Never {@code double}. Binary floating point cannot represent 0.1
     * exactly, so summing six ticket prices as doubles can produce
     * 2699.9999999999995 and a customer who sees a stray paisa on their receipt.
     * Integers in minor units make every total exact by construction.
     */
    @Column(name = "price_minor", nullable = false)
    private Long priceMinor;

    /**
     * The hex colour the seat map paints this tier with. Living in the database
     * next to the price means the legend and the seat fill can never disagree
     * with each other, which is what happens when the colour is a magic constant
     * in a stylesheet.
     */
    @Column(nullable = false, length = 9)
    private String colour;

    @Column(name = "sort_order", nullable = false)
    private Short sortOrder;

    protected PriceTier() { }

    public Long getId() { return id; }
    public Event getEvent() { return event; }
    public String getName() { return name; }
    public Long getPriceMinor() { return priceMinor; }
    public String getColour() { return colour; }
    public Short getSortOrder() { return sortOrder; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PriceTier other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() { return PriceTier.class.hashCode(); }
}
