package com.seatlock.web;

import com.seatlock.booking.BookingService;
import com.seatlock.security.AuthenticatedUser;
import com.seatlock.web.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "Bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    // ---- Holds -------------------------------------------------------

    @PostMapping("/holds/{holdId}/extend")
    @Operation(summary = "Extend an active reservation once")
    public ExtendHoldResponse extend(@AuthenticationPrincipal AuthenticatedUser caller,
                                     @PathVariable UUID holdId) {
        return bookingService.extendHold(caller, holdId);
    }

    @DeleteMapping("/holds/{holdId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Release a reservation early")
    public void release(@AuthenticationPrincipal AuthenticatedUser caller,
                        @PathVariable UUID holdId) {
        bookingService.releaseHold(caller, holdId);
    }

    // ---- Bookings ----------------------------------------------------

    /**
     * Confirm and pay.
     *
     * <p>{@code Idempotency-Key} is {@code required = true}, so a client that
     * omits it gets a 400 rather than an unprotected booking. Making it optional
     * would mean the protection only applies to clients that remembered to ask
     * for it - which is precisely the clients that did not need reminding.
     *
     * <p>The key is validated for length and non-blankness. It is used as part of
     * a database key, so an unbounded client-supplied string is not something to
     * accept without limits.
     */
    @PostMapping("/bookings/{holdId}/confirm")
    @Operation(summary = "Pay for a held reservation")
    public BookingDetail confirm(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @PathVariable UUID holdId,
            @RequestHeader(value = "Idempotency-Key")
            @NotBlank(message = "An Idempotency-Key header is required")
            @Size(max = 120, message = "Idempotency-Key is too long")
            String idempotencyKey,
            @Valid @RequestBody ConfirmBookingRequest request) {

        return bookingService.confirm(caller, holdId, request, idempotencyKey);
    }

    @GetMapping("/bookings")
    @Operation(summary = "The caller's bookings, newest first")
    public PageResponse<BookingSummary> list(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {

        return bookingService.listBookings(caller, PageRequest.of(page, size));
    }

    /**
     * One booking.
     *
     * <p>There is no {@code @PreAuthorize} here and no ownership check in this
     * method, and that is deliberate rather than an omission: the ownership
     * filter is in the repository query itself
     * ({@code BookingRepository.findOwned}), so a booking belonging to somebody
     * else simply does not exist as far as this code path is concerned, and the
     * caller gets a 404.
     *
     * <p>404 rather than 403 is the right answer. A 403 confirms that a booking
     * with that id exists, which lets an attacker enumerate the id space even
     * though they cannot read any of it.
     */
    @GetMapping("/bookings/{bookingId}")
    @Operation(summary = "One of the caller's bookings")
    public BookingDetail get(@AuthenticationPrincipal AuthenticatedUser caller,
                             @PathVariable UUID bookingId) {
        return bookingService.getBooking(caller, bookingId);
    }

    @PostMapping("/bookings/{bookingId}/cancel")
    @Operation(summary = "Cancel a booking and return its seats to sale")
    public BookingDetail cancel(@AuthenticationPrincipal AuthenticatedUser caller,
                                @PathVariable UUID bookingId) {
        return bookingService.cancel(caller, bookingId);
    }
}
