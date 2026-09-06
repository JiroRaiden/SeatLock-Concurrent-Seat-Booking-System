package com.seatlock.web;

import com.seatlock.security.AuthService;
import com.seatlock.security.AuthenticatedUser;
import com.seatlock.web.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @Operation(summary = "Create an account and receive a token pair")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        // 201 with the tokens, so a new user is signed in immediately rather
        // than being bounced to a login form they just filled in.
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    @Operation(summary = "Exchange credentials for a token pair")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange a refresh token for a new pair (rotates the token)")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke a refresh token")
    public void logout(@Valid @RequestBody RefreshRequest request) {
        // Always 204, even for an unknown token. A client calling logout is
        // trying to end a session; answering 404 would leave them holding
        // something they believe is still live. It also avoids confirming
        // whether a given token string is real.
        authService.logout(request.refreshToken());
    }

    @GetMapping("/me")
    @Operation(summary = "The current caller")
    public UserSummary me(@AuthenticationPrincipal AuthenticatedUser caller) {
        // Everything here came out of the verified JWT, so this endpoint needs
        // no database query at all - which is exactly what a stateless access
        // token buys you.
        return new UserSummary(caller.id(), caller.email(), null, caller.role().name());
    }
}
