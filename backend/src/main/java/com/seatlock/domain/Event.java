package com.seatlock.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A showing: this film, in this auditorium, at this time.
 */
@Entity
@Table(name = "events")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_id", nullable = false)
    private Venue venue;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 200)
    private String subtitle;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private EventCategory category;

    @Column(length = 40)
    private String language;

    @Column(length = 16)
    private String certification;

    @Column(name = "duration_minutes")
    private Short durationMinutes;

    @Column(name = "poster_url", length = 500)
    private String posterUrl;

    @Column(name = "backdrop_url", length = 500)
    private String backdropUrl;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    /** After this instant the API refuses new holds for this event. */
    @Column(name = "sales_close_at", nullable = false)
    private Instant salesCloseAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventStatus status = EventStatus.DRAFT;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Event() { }

    /**
     * Whether this event will accept new seat holds right now.
     *
     * <p>Checked server-side on every hold request. The frontend also hides the
     * button once sales close, but that is a courtesy to the user, not a control:
     * anyone can POST to the API directly, so the authoritative check must be
     * here. "The UI prevents it" is never a security answer.
     */
    public boolean isOpenForSale(Instant now) {
        return status == EventStatus.PUBLISHED && now.isBefore(salesCloseAt);
    }

    public Long getId() { return id; }
    public Venue getVenue() { return venue; }
    public String getTitle() { return title; }
    public String getSubtitle() { return subtitle; }
    public String getDescription() { return description; }
    public EventCategory getCategory() { return category; }
    public String getLanguage() { return language; }
    public String getCertification() { return certification; }
    public Short getDurationMinutes() { return durationMinutes; }
    public String getPosterUrl() { return posterUrl; }
    public String getBackdropUrl() { return backdropUrl; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getSalesCloseAt() { return salesCloseAt; }
    public EventStatus getStatus() { return status; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Event other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() { return Event.class.hashCode(); }
}
