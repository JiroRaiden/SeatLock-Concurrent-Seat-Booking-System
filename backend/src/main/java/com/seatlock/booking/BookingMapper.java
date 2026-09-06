package com.seatlock.booking;

import com.seatlock.domain.Booking;
import com.seatlock.domain.BookingSeat;
import com.seatlock.web.dto.*;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Entity to DTO conversion for bookings.
 *
 * <p>Its own class rather than methods on the entity, because the entity should
 * not know that HTTP exists, and because a mapper is the natural place to
 * enforce the rule that <b>no entity is ever serialised directly</b>. Every
 * field that reaches a client passes through code somebody had to write on
 * purpose - which is why {@code passwordHash}, {@code version} and internal ids
 * cannot leak by accident.
 *
 * <p>Every method here must be called while the JPA session is still open, or
 * with the lazy associations already initialised. That constraint is the reason
 * {@code open-in-view} is switched off in application.yml: with it on, lazy
 * loading silently keeps working during view rendering, and you end up firing
 * database queries from the serialisation layer without ever realising it.
 */
@Component
public class BookingMapper {

    public BookingDetail toDetail(Booking booking) {
        List<BookingSeatDto> seats = booking.getSeats().stream()
                // Sort so the ticket lists seats in the order somebody would read
                // them off a printed stub, not in whatever order the rows came
                // back from the database.
                .sorted(Comparator
                        .comparing((BookingSeat bs) -> bs.getEventSeat().getSeat().getRowIndex())
                        .thenComparing(bs -> bs.getEventSeat().getSeat().getSeatNumber()))
                .map(bs -> new BookingSeatDto(
                        bs.getEventSeat().getId(),
                        bs.getEventSeat().getSeat().label(),
                        bs.getEventSeat().getSeat().getSection(),
                        bs.getPriceMinor()))
                .toList();

        // Seat counts are irrelevant on a booking receipt, so they are null here
        // rather than triggering an extra aggregate query nobody will read.
        EventSummary event = EventSummary.from(booking.getEvent(), null, null, null);

        return new BookingDetail(
                booking.getPublicId(),
                booking.getReference(),
                booking.getStatus().name(),
                booking.getTotalMinor(),
                booking.getCreatedAt(),
                booking.getExpiresAt(),
                booking.getConfirmedAt(),
                booking.getCancelledAt(),
                event,
                seats);
    }

    /**
     * The list view.
     *
     * <p>{@code seatCount} rather than the seats themselves. Loading every seat
     * of every booking to display "3 seats" is the N+1 problem wearing a
     * disguise; the caller supplies the count from a query that already has it.
     */
    public BookingSummary toSummary(Booking booking, int seatCount) {
        return new BookingSummary(
                booking.getPublicId(),
                booking.getReference(),
                booking.getStatus().name(),
                booking.getTotalMinor(),
                seatCount,
                booking.getCreatedAt(),
                booking.getExpiresAt(),
                booking.getEvent().getTitle(),
                booking.getEvent().getStartsAt(),
                booking.getEvent().getVenue().getName());
    }
}
