package com.seatlock.web;

import com.seatlock.booking.BookingService;
import com.seatlock.booking.EventService;
import com.seatlock.domain.EventCategory;
import com.seatlock.security.AuthenticatedUser;
import com.seatlock.web.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@Validated   // makes the @Min/@Max on query parameters below actually enforced
@Tag(name = "Events")
public class EventController {

    private final EventService eventService;
    private final BookingService bookingService;

    public EventController(EventService eventService, BookingService bookingService) {
        this.eventService = eventService;
        this.bookingService = bookingService;
    }

    @GetMapping("/events")
    @Operation(summary = "Browse published events")
    public PageResponse<EventSummary> browse(
            @RequestParam(required = false) EventCategory category,
            @RequestParam(required = false) @Size(max = 100) String city,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            // The cap is a real control, not politeness. Without it, ?size=100000
            // is a one-line denial of service: the database materialises every
            // row, the JVM holds them all, and the response takes megabytes.
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {

        Pageable pageable = PageRequest.of(page, size);
        return eventService.browse(category, city, q, pageable);
    }

    @GetMapping("/events/{eventId}")
    @Operation(summary = "One event, with its price tiers")
    public EventDetail detail(@PathVariable Long eventId) {
        return eventService.detail(eventId);
    }

    @GetMapping("/events/{eventId}/seatmap")
    @Operation(summary = "Every seat for an event, with live hold state")
    public SeatMapResponse seatMap(@PathVariable Long eventId) {
        return eventService.seatMap(eventId);
    }

    @GetMapping("/cities")
    @Operation(summary = "Cities that currently have published events")
    public List<String> cities() {
        return eventService.cities();
    }

    // ------------------------------------------------------------------
    // Holds. Nested under /events/{id} because a hold only means anything in
    // the context of one event, and because it lets the rate limiter match the
    // path without needing to look at a request body.
    // ------------------------------------------------------------------

    @PostMapping("/events/{eventId}/holds")
    @Operation(summary = "Reserve seats for a few minutes")
    public ResponseEntity<HoldResponse> createHold(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @PathVariable Long eventId,
            @Valid @RequestBody CreateHoldRequest request) {

        HoldResponse response = bookingService.createHold(caller, eventId, request.seatIds());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
