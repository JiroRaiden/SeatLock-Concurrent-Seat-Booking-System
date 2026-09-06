package com.seatlock.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefreshRequest(
        @NotBlank(message = "A refresh token is required")
        @Size(max = 200)
        String refreshToken
) { }
