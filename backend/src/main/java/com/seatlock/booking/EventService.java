package com.seatlock.booking;

import com.seatlock.domain.*;
import com.seatlock.exception.ApiException;
import com.seatlock.hold.SeatHoldService;
import com.seatlock.repository.EventRepository;
import com.seatlock.repository.EventSeatRepository;
import com.seatlock.repository.PriceTierRepository;
import com.seatlock.web.dto.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Reads for the browse and seat-map screens.
 */
@Service
public class EventService {

    private final EventRepository events;
    private final EventSeatRepository eventSeats;
    private final PriceTierRepository priceTiers;
    private final SeatHoldService holds;

    public EventService(EventRepository events,
                        EventSeatRepository eventSeats,
                        PriceTierRepository priceTiers,
                        SeatHoldService holds) {
        this.events = events;
        this.eventSeats = eventSeats;
        this.priceTiers = priceTiers;
        this.holds = holds;
    }

    /**
     * The browse page.
     *
     * <h2>Three queries, never 3 + 2N</h2>
     *
     * A page of 20 event cards needs, for each card, the event, its venue, its
     * cheapest price, and its seat counts. The obvious loop does one query for
     * the page and then two per card - 41 queries to render one screen, each one
     * a network round trip to Postgres.
     *
     * <p>Instead: one paged query (with the venue join-fetched), one grouped
     * query for all the minimum prices, one grouped query for all the counts.
     * Three queries regardless of page size. The lookups are then done in memory
     * against two small maps.
     *
     * <p>This is the single most valuable habit in JPA code, and the easiest to
     * lose - the N+1 version is shorter, reads perfectly well, and behaves fine
     * with six rows of test data.
     */
    @Transactional(readOnly = true)
    public PageResponse<EventSummary> browse(EventCategory category,
                                             String city,
                                             String query,
                                             Pageable pageable) {

        Page<Event> page = events.search(EventStatus.PUBLISHED, category,
                blankToNull(city), blankToNull(query), pageable);

        List<Long> ids = page.getContent().stream().map(Event::getId).toList();
        if (ids.isEmpty()) {
            return PageResponse.of(List.of(), page);
        }

        Map<Long, Long> minPrices = toMap(events.minPriceByEventIds(ids));

        Map<Long, long[]> counts = new HashMap<>();
        for (Object[] row : eventSeats.countsByEventIds(ids)) {
            counts.put(((Number) row[0]).longValue(),
                       new long[]{ ((Number) row[1]).longValue(), ((Number) row[2]).longValue() });
        }

        List<EventSummary> content = page.getContent().stream()
                .map(e -> {
                    long[] c = counts.getOrDefault(e.getId(), new long[]{0, 0});
                    return EventSummary.from(e, minPrices.get(e.getId()), c[0], c[1]);
                })
                .toList();

        return PageResponse.of(content, page);
    }

    @Transactional(readOnly = true)
    public EventDetail detail(Long eventId) {
        Event event = events.findWithVenue(eventId)
                .orElseThrow(() -> ApiException.notFound("event"));

        // A DRAFT or CANCELLED event is not found, as far as the public is
        // concerned. Returning it with a status field would let anyone read
        // unannounced line-ups by walking the id space.
        if (event.getStatus() != EventStatus.PUBLISHED) {
            throw ApiException.notFound("event");
        }

        List<PriceTierDto> tiers = priceTiers.findByEventIdOrderBySortOrderAsc(eventId).stream()
                .map(PriceTierDto::from)
                .toList();

        long available = eventSeats.countByEventIdAndStatus(eventId, SeatStatus.AVAILABLE);
        long total = eventSeats.findIdsByEventId(eventId).size();

        return EventDetail.from(event, tiers, available, total);
    }

    /**
     * The seat map: every seat of one event, grouped into rows, with live hold
     * state overlaid.
     *
     * <h2>Where HELD comes from</h2>
     *
     * The database knows AVAILABLE, BOOKED and BLOCKED. It does not know HELD,
     * because holds are not stored there - that is the central design decision
     * of this project. So the status a browser sees is composed from two sources
     * on every request:
     *
     * <pre>
     *   Postgres: this seat is AVAILABLE
     *   Redis:    ...but somebody is holding it right now
     *   ------------------------------------------------
     *   client:   HELD
     * </pre>
     *
     * <p>Two calls, not two hundred: one query for the seat rows, one Redis
     * MGET for the whole auditorium's hold keys.
     *
     * <p>The result is advisory and already slightly stale by the time it
     * renders - a hold can expire in the time it takes the response to travel.
     * That is fine, and worth being explicit about: this exists to reduce
     * disappointment, not to enforce anything. Enforcement happens when the user
     * presses Proceed, against Redis and Postgres, not against a picture drawn
     * three seconds ago.
     */
    @Transactional(readOnly = true)
    public SeatMapResponse seatMap(Long eventId) {
        Event event = events.findWithVenue(eventId)
                .orElseThrow(() -> ApiException.notFound("event"));
        if (event.getStatus() != EventStatus.PUBLISHED) {
            throw ApiException.notFound("event");
        }

        List<EventSeat> seats = eventSeats.findSeatMap(eventId);

        // One round trip for the entire screen.
        Set<Long> heldIds = holds.findHeldSeatIds(
                eventId, seats.stream().map(EventSeat::getId).toList());

        long available = 0, booked = 0, blocked = 0, held = 0;

        // LinkedHashMap preserves the SQL ordering (rowIndex, colIndex), so rows
        // come out front-to-back without a second sort.
        Map<String, List<SeatMapSeat>> byRow = new LinkedHashMap<>();
        Map<String, Short> rowIndexes = new LinkedHashMap<>();
        Map<String, String> rowSections = new LinkedHashMap<>();

        for (EventSeat es : seats) {
            String status;
            if (es.getStatus() == SeatStatus.AVAILABLE && heldIds.contains(es.getId())) {
                status = "HELD";
                held++;
            } else {
                status = es.getStatus().name();
                switch (es.getStatus()) {
                    case AVAILABLE -> available++;
                    case BOOKED -> booked++;
                    case BLOCKED -> blocked++;
                }
            }

            Seat seat = es.getSeat();
            String rowLabel = seat.getRowLabel();

            byRow.computeIfAbsent(rowLabel, k -> new ArrayList<>())
                 .add(new SeatMapSeat(
                         es.getId(),
                         seat.label(),
                         seat.getSeatNumber(),
                         seat.getColIndex(),
                         es.getPriceTier().getId(),
                         es.getPriceTier().getPriceMinor(),
                         status));

            rowIndexes.putIfAbsent(rowLabel, seat.getRowIndex());
            rowSections.putIfAbsent(rowLabel, seat.getSection());
        }

        List<SeatMapRow> rows = byRow.entrySet().stream()
                .map(entry -> new SeatMapRow(
                        entry.getKey(),
                        rowIndexes.get(entry.getKey()),
                        rowSections.get(entry.getKey()),
                        entry.getValue()))
                .toList();

        List<PriceTierDto> tiers = priceTiers.findByEventIdOrderBySortOrderAsc(eventId).stream()
                .map(PriceTierDto::from)
                .toList();

        return new SeatMapResponse(
                event.getId(),
                event.getTitle(),
                event.getVenue().getName(),
                event.getStartsAt(),
                event.getSalesCloseAt(),
                tiers,
                rows,
                new SeatMapSummary(available, booked, blocked, held));
    }

    @Transactional(readOnly = true)
    public List<String> cities() {
        return events.findActiveCities();
    }

    // ------------------------------------------------------------------

    private static Map<Long, Long> toMap(List<Object[]> rows) {
        Map<Long, Long> map = new HashMap<>();
        for (Object[] row : rows) {
            map.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return map;
    }

    /**
     * Turns "" and "   " into null so the {@code :param IS NULL OR ...} clauses
     * in the search query treat an empty filter box as "no filter" rather than
     * as "match the empty string".
     */
    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
