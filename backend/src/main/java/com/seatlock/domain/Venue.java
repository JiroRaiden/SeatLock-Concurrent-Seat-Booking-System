package com.seatlock.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "venues")
public class Venue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 100)
    private String city;

    @Column(nullable = false, length = 400)
    private String address;

    /**
     * Denormalised count of this venue's seats. Kept because the event list page
     * shows capacity for every card, and a COUNT(*) per card is a needless
     * query. Written once when the seat map is created; venues do not grow seats
     * on their own.
     */
    @Column(name = "seat_count", nullable = false)
    private Integer seatCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Venue() { }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getCity() { return city; }
    public String getAddress() { return address; }
    public Integer getSeatCount() { return seatCount; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Venue other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() { return Venue.class.hashCode(); }
}
