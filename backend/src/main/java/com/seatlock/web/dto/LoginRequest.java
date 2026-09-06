package com.seatlock.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Login payload.
 *
 * <p>Note the absence of {@code @Email} here, unlike registration. Validating
 * the format on login would answer "is this even an email?" before the
 * credential check runs, which produces a different response for a malformed
 * address than for a wrong password. Small, but it is one more signal than we
 * need to give. Everything that is not a valid credential gets the same answer.
 */
public record LoginRequest(

        @NotBlank(message = "Email is required")
        @Size(max = 320)
        String email,

        @NotBlank(message = "Password is required")
        @Size(max = 128)
        String password
) { }
