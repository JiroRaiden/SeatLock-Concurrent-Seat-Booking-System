package com.seatlock.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatlock.support.IntegrationTestBase;
import com.seatlock.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rate limiting on the auth endpoints.
 *
 * <h2>Why this is a separate class from {@link SecurityIT}</h2>
 *
 * Because proving a rate limit works requires a limit low enough to hit, and a
 * limit that low would throttle every other test that logs in. The test profile
 * therefore sets the budget absurdly high (10,000/minute) and this one class
 * overrides it to three.
 *
 * <p>{@code @TestPropertySource} gives the override its own Spring context, so
 * the tight limit is scoped to the tests that need it and cannot leak into a
 * neighbouring class as a mysterious intermittent 429. It also means the
 * {@code RateLimitFilter}'s in-memory buckets start empty here, which matters:
 * they are per-bean state, so a shared context would carry consumed tokens
 * between test classes and make the outcome depend on execution order.
 *
 * <h2>Why the login attempts are deliberately invalid</h2>
 *
 * The limiter runs <em>before</em> authentication - that is the whole point,
 * since BCrypt costs us ~250ms of CPU per attempt and a limiter placed after it
 * would already have paid the price it exists to avoid. So a rejected login
 * consumes a token exactly like a successful one, and the test needs no
 * accounts, no registration, and no correct passwords.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "seatlock.security.rate-limit.auth-requests-per-minute=3")
@DisplayName("Rate limiting: the auth endpoints have a per-IP budget")
class AuthRateLimitIT extends IntegrationTestBase {

    private static final int BUDGET = 3;
    private static final int ATTEMPTS = 5;

    @LocalServerPort
    private int port;

    @Autowired private TestFixtures fixtures;
    @Autowired private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeEach
    void resetState() {
        fixtures.reset();
    }

    @Test
    @DisplayName("the fourth and fifth login in a burst are refused with 429 and a Retry-After")
    void loginBurstIsThrottled() throws Exception {
        List<HttpResponse<String>> responses = new ArrayList<>();
        for (int i = 0; i < ATTEMPTS; i++) {
            responses.add(login("nobody@test.dev", "WhateverPassword"));
        }

        List<HttpResponse<String>> throttled = responses.stream()
                .filter(r -> r.statusCode() == 429).toList();
        List<HttpResponse<String>> allowed = responses.stream()
                .filter(r -> r.statusCode() != 429).toList();

        // A token bucket refills continuously rather than resetting on a window
        // boundary, so three tokens per minute is one token every twenty
        // seconds. Five requests fired back to back take well under a second,
        // which is nowhere near long enough for a refill - so the split is exact
        // rather than approximate.
        assertThat(allowed)
                .as("the configured budget is spent first")
                .hasSize(BUDGET);
        assertThat(throttled)
                .as("everything past the budget is refused")
                .hasSize(ATTEMPTS - BUDGET);

        // The allowed ones must still have been genuinely rejected as logins:
        // the limiter must not turn a credential check into a free pass.
        allowed.forEach(r -> assertThat(r.statusCode()).isEqualTo(401));

        HttpResponse<String> refused = throttled.get(0);

        // Telling a client exactly when to come back is what stops a
        // well-behaved one from retrying in a tight loop and making the
        // situation worse. Without it, the polite clients hurt you as much as
        // the impolite ones.
        assertThat(refused.headers().firstValue("Retry-After"))
                .as("a 429 without Retry-After is an invitation to hammer")
                .isPresent();
        assertThat(Long.parseLong(refused.headers().firstValue("Retry-After").orElseThrow()))
                .isPositive();

        JsonNode error = objectMapper.readTree(refused.body());
        assertThat(error.path("code").asText()).isEqualTo("TOO_MANY_REQUESTS");
        assertThat(error.path("status").asInt()).isEqualTo(429);
        assertThat(error.path("details").path("retryAfterSeconds").asLong()).isPositive();
    }

    @Test
    @DisplayName("browsing is not charged against the auth budget")
    void publicBrowsingIsNotRateLimited() throws Exception {
        // The limiter matches on path, so an over-broad matcher would silently
        // throttle the busiest read endpoint in the product. Ten requests is
        // more than three; if they all pass, /events is genuinely outside the
        // auth bucket rather than merely under its limit.
        for (int i = 0; i < 10; i++) {
            HttpResponse<String> response = send(HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/api/v1/events"))
                    .timeout(Duration.ofSeconds(20))
                    .GET().build());
            assertThat(response.statusCode()).isEqualTo(200);
        }
    }

    // ------------------------------------------------------------------

    private HttpResponse<String> login(String email, String password) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("email", email, "password", password));
        return send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
