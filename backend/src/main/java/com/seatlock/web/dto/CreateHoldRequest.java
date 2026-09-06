package com.seatlock.web.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * "Reserve these seats for me."
 *
 * <p>The {@code @Size} cap is a real control, not a nicety. Without it a single
 * request could ask to hold ten thousand seats, which would build a ten-thousand
 * key Lua script and block Redis's single command-execution thread while it ran.
 * Bean validation rejects that before any of our code sees it.
 *
 * <p>The maximum is duplicated here (a constant annotation value, which Java
 * requires) and in {@code HoldProperties.maxSeatsPerBooking} (configurable, and
 * re-checked in the service). Belt and braces: the annotation gives a clean 400
 * with a helpful message, and the service check is what actually enforces the
 * configured business rule.
 */
public record CreateHoldRequest(

        @NotEmpty(message = "Select at least one seat")
        @Size(max = 10, message = "You can book at most 10 seats at a time")
        List<@Positive(message = "Seat ids must be positive") Long> seatIds
) { }
