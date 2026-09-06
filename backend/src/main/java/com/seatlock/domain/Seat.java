package com.seatlock.domain;

import jakarta.persistence.*;

/**
 * A physical seat bolted to a venue floor. Independent of any event.
 */
@Entity
@Table(name = "seats")
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_id", nullable = false)
    private Venue venue;

    /** RECLINER / PRIME / CLASSIC. Matches a {@link PriceTier} name per event. */
    @Column(nullable = false, length = 40)
    private String section;

    /** What is printed on the ticket: "A", "H", "AA". A display string. */
    @Column(name = "row_label", nullable = false, length = 4)
    private String rowLabel;

    @Column(name = "seat_number", nullable = false)
    private Short seatNumber;

    /**
     * Rendering coordinates, kept separate from the label. rowIndex is what you
     * sort by; rowLabel is what you show. They differ because cinemas skip row
     * "I" (it reads as the digit 1 on a printed ticket), so label order and
     * physical order are not the same sequence.
     */
    @Column(name = "row_index", nullable = false)
    private Short rowIndex;

    /** Column position including the aisle gap, so the UI need not guess. */
    @Column(name = "col_index", nullable = false)
    private Short colIndex;

    protected Seat() { }

    public Long getId() { return id; }
    public Venue getVenue() { return venue; }
    public String getSection() { return section; }
    public String getRowLabel() { return rowLabel; }
    public Short getSeatNumber() { return seatNumber; }
    public Short getRowIndex() { return rowIndex; }
    public Short getColIndex() { return colIndex; }

    /** "H12" - the form used in the UI and on tickets. */
    public String label() {
        return rowLabel + seatNumber;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Seat other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() { return Seat.class.hashCode(); }
}
