package com.seatlock.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Payment details for confirming a booking.
 *
 * <p>There is no card number here, and there never should be. The client
 * exchanges the card with the payment provider directly and receives a
 * single-use token; only that token reaches us. Card data never touches this
 * server, our logs, our database or our backups - which is both the correct
 * design and the reason a system like this stays outside the most demanding
 * parts of PCI-DSS scope.
 *
 * <p>This project stubs the provider. The stub accepts {@code tok_demo_success}
 * and declines {@code tok_demo_decline}, which is enough to exercise both paths
 * in tests without pretending to be a payment gateway.
 */
public record ConfirmBookingRequest(

        @NotBlank(message = "Payment method is required")
        @Pattern(regexp = "CARD|UPI|NETBANKING", message = "Unsupported payment method")
        String paymentMethod,

        @NotBlank(message = "Payment token is required")
        @Size(max = 200)
        // Whitelist the shape. A token is opaque to us, but "opaque" must not
        // mean "anything at all" - constraining the character set closes off a
        // whole class of injection into whatever consumes it downstream.
        @Pattern(regexp = "^[A-Za-z0-9_\\-]{8,200}$", message = "Malformed payment token")
        String paymentToken
) { }
