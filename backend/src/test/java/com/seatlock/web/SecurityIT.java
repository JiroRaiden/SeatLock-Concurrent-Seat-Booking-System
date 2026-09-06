package com.seatlock.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatlock.config.SecurityProperties;
import com.seatlock.domain.User;
import com.seatlock.repository.UserRepository;
import com.seatlock.security.JwtService;
import com.seatlock.support.IntegrationTestBase;
import com.seatlock.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The authorization boundaries, exercised over real HTTP.
 *
 * <h2>Why these particular tests</h2>
 *
 * Security bugs do not announce themselves. A broken ownership check still
 * returns 200 with a perfectly well-formed body; a leaky error message still
 * looks like a normal error. So every test here asserts on the thing an attacker
 * would actually measure - the status code, the exact bytes of the error, and
 * whether two different failures can be told apart.
 *
 * <p>Three of them are worth reading even if you skip the rest: the
 * indistinguishable login failures, the 404-not-403 for someone else's booking,
 * and the rejected {@code role} field. Each corresponds to a named vulnerability
 * class (account enumeration, IDOR, mass assignment) that this codebase claims
 * to be immune to, and a claim with no test behind it is just a comment.
 *
 * <p>Rate limiting lives in {@link AuthRateLimitIT} instead, because proving it
 * requires a tight limit and a tight limit would throttle everything here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Security: authentication and authorization boundaries")
class SecurityIT extends IntegrationTestBase {

    private static final String PASSWORD = "CorrectHorseBattery";

    @LocalServerPort
    private int port;

    @Autowired private TestFixtures fixtures;
    @Autowired private UserRepository users;
    @Autowired private JwtService jwtService;
    @Autowired private SecurityProperties securityProperties;
    @Autowired private StringRedisTemplate redis;
    @Autowired private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeEach
    void resetState() {
        fixtures.reset();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    // ==================================================================
    // The ordinary path
    // ==================================================================

    @Test
    @DisplayName("register, then login, then /auth/me works end to end")
    void registerLoginAndMe() throws Exception {
        HttpResponse<String> registered = post(null, "/api/v1/auth/register", Map.of(
                "email", "alice@test.dev",
                "password", PASSWORD,
                "displayName", "Alice"));

        assertThat(registered.statusCode()).isEqualTo(201);
        JsonNode signup = objectMapper.readTree(registered.body());
        assertThat(signup.path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(signup.path("user").path("role").asText())
                .as("the server assigns the role; the client never gets a say")
                .isEqualTo("ROLE_USER");
        // The one field that must never appear in any response, ever.
        assertThat(registered.body()).doesNotContain("passwordHash");

        HttpResponse<String> loggedIn = post(null, "/api/v1/auth/login", Map.of(
                "email", "alice@test.dev",
                "password", PASSWORD));
        assertThat(loggedIn.statusCode()).isEqualTo(200);

        String accessToken = objectMapper.readTree(loggedIn.body()).path("accessToken").asText();

        HttpResponse<String> me = send(request(accessToken, "/api/v1/auth/me").GET().build());
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(me.body()).path("email").asText()).isEqualTo("alice@test.dev");
    }

    // ==================================================================
    // Account enumeration
    // ==================================================================

    @Test
    @DisplayName("a wrong password and an unknown email produce the identical 401")
    void loginFailuresAreIndistinguishable() throws Exception {
        fixtures.createUser("alice@test.dev");

        // Control: the account exists and the right password gets in. Without
        // this, two 401s could just as easily mean the fixture never landed.
        assertThat(post(null, "/api/v1/auth/login", Map.of(
                "email", "alice@test.dev", "password", TestFixtures.FIXTURE_PASSWORD))
                .statusCode()).isEqualTo(200);

        HttpResponse<String> wrongPassword = post(null, "/api/v1/auth/login", Map.of(
                "email", "alice@test.dev", "password", "TotallyWrongPassword"));

        HttpResponse<String> unknownEmail = post(null, "/api/v1/auth/login", Map.of(
                "email", "nobody@test.dev", "password", PASSWORD));

        assertThat(wrongPassword.statusCode()).isEqualTo(401);
        assertThat(unknownEmail.statusCode()).isEqualTo(401);

        JsonNode a = objectMapper.readTree(wrongPassword.body());
        JsonNode b = objectMapper.readTree(unknownEmail.body());

        // Compared field by field rather than whole-body, because the envelope
        // carries a per-error timestamp and traceId that are SUPPOSED to differ.
        // Everything an attacker could branch on must not.
        assertThat(a.path("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(b.path("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(b.path("message").asText())
                .as("if these two messages ever differ, the login endpoint becomes a "
                    + "free service for testing which email addresses have accounts here")
                .isEqualTo(a.path("message").asText());
        assertThat(b.path("status").asInt()).isEqualTo(a.path("status").asInt());

        // The message must also not accidentally name which half was wrong.
        assertThat(a.path("message").asText().toLowerCase())
                .doesNotContain("no account")
                .doesNotContain("not found")
                .doesNotContain("does not exist");
    }

    // ==================================================================
    // IDOR
    // ==================================================================

    /**
     * Insecure Direct Object Reference: the resource id is guessable-ish and the
     * only thing standing between a stranger and the data is a check that
     * somebody has to remember to write.
     *
     * <p><b>404, not 403, and the difference is the entire point.</b> A 403 says
     * "this exists, but it is not yours" - which is a working oracle. Feed it a
     * list of ids and it partitions them into real and imaginary, for free, from
     * an unprivileged account. Since we are not going to show the resource
     * either way, denying its existence costs nothing and leaks nothing.
     *
     * <p>Here it comes out of the repository rather than out of a guard clause:
     * {@code BookingRepository.findOwned} puts the owner in the WHERE clause, so
     * "not found" and "not yours" are literally the same query returning zero
     * rows. There is no separate check left for a future endpoint to forget.
     */
    @Test
    @DisplayName("IDOR: another user's booking is a 404, never a 403")
    void anotherUsersBookingIsNotFound() throws Exception {
        TestFixtures.Auditorium hall = fixtures.createAuditorium(1, 4);
        String aliceToken = mintTokenFor("alice@test.dev", "ROLE_USER");
        String bobToken = mintTokenFor("bob@test.dev", "ROLE_USER");

        HttpResponse<String> held = post(aliceToken,
                "/api/v1/events/" + hall.eventId() + "/holds",
                Map.of("seatIds", List.of(hall.firstSeat())));
        assertThat(held.statusCode()).isEqualTo(201);
        String bookingId = objectMapper.readTree(held.body()).path("holdId").asText();

        // Control: the id is real and readable by its owner, so a 404 for Bob
        // cannot be dismissed as "the id was wrong".
        assertThat(send(request(aliceToken, "/api/v1/bookings/" + bookingId).GET().build())
                .statusCode()).isEqualTo(200);

        HttpResponse<String> bobsAttempt =
                send(request(bobToken, "/api/v1/bookings/" + bookingId).GET().build());

        assertThat(bobsAttempt.statusCode())
                .as("403 would confirm the booking exists; 404 tells Bob nothing at all")
                .isEqualTo(404);
        assertThat(objectMapper.readTree(bobsAttempt.body()).path("code").asText())
                .isEqualTo("NOT_FOUND");
        // And nothing about Alice leaks in the message either.
        assertThat(bobsAttempt.body()).doesNotContain("alice@test.dev");
    }

    // ==================================================================
    // Tokens
    // ==================================================================

    @Test
    @DisplayName("an unauthenticated call to a protected endpoint gets the standard JSON envelope")
    void unauthenticatedRequestsGetJsonNotAnHtmlErrorPage() throws Exception {
        HttpResponse<String> response = send(request(null, "/api/v1/bookings").GET().build());

        assertThat(response.statusCode()).isEqualTo(401);

        // This is why SecurityConfig writes the envelope itself. Failures inside
        // the filter chain happen before a controller is chosen, so
        // @RestControllerAdvice never sees them - and without that entry point,
        // a 401 would arrive as Spring's HTML error page while every other error
        // was JSON. The frontend's error parser would then break on exactly the
        // response it most needs to understand.
        assertThat(response.headers().firstValue("content-type").orElse(""))
                .contains("application/json");
        assertThat(objectMapper.readTree(response.body()).path("code").asText())
                .isEqualTo("UNAUTHENTICATED");
    }

    @Test
    @DisplayName("a token with one character flipped in the signature is rejected")
    void tamperedSignatureIsRejected() throws Exception {
        String valid = mintTokenFor("alice@test.dev", "ROLE_USER");
        assertThat(send(request(valid, "/api/v1/auth/me").GET().build()).statusCode()).isEqualTo(200);

        // The last character is inside the HMAC signature. Changing one bit of
        // it is enough: the whole security of HS256 is that you cannot produce a
        // valid signature without the key, and cannot patch an existing one.
        char last = valid.charAt(valid.length() - 1);
        String tampered = valid.substring(0, valid.length() - 1) + (last == 'A' ? 'B' : 'A');

        HttpResponse<String> response = send(request(tampered, "/api/v1/auth/me").GET().build());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(objectMapper.readTree(response.body()).path("code").asText())
                .isEqualTo("UNAUTHENTICATED");
    }

    @Test
    @DisplayName("a token signed with a different key is rejected")
    void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        long id = fixtures.createUser("alice@test.dev");
        User alice = users.findById(id).orElseThrow();

        // Same issuer, same claims, same algorithm - only the key differs. This
        // is the forgery an attacker attempts once they know the token format,
        // and the only thing standing in the way is that they cannot guess 256
        // bits. (Which is why JwtService refuses to start on a shorter key.)
        JwtService foreign = jwtServiceWith(randomBase64Key(),
                securityProperties.getJwt().getIssuer(), Duration.ofMinutes(15));

        HttpResponse<String> response = send(
                request(foreign.issueAccessToken(alice), "/api/v1/auth/me").GET().build());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("an expired access token is rejected")
    void expiredTokenIsRejected() throws Exception {
        long id = fixtures.createUser("alice@test.dev");
        User alice = users.findById(id).orElseThrow();

        // A negative TTL back-dates the exp claim, which is the same state a
        // token reaches fifteen minutes after it was issued. Five minutes into
        // the past, comfortably beyond the parser's 30-second clock-skew
        // tolerance - a one-second overshoot would make this test flaky.
        JwtService expiring = jwtServiceWith(securityProperties.getJwt().getSecret(),
                securityProperties.getJwt().getIssuer(), Duration.ofMinutes(-5));

        HttpResponse<String> response = send(
                request(expiring.issueAccessToken(alice), "/api/v1/auth/me").GET().build());

        // The access token cannot be revoked, so its lifetime IS the compromise
        // window. That trade-off is only acceptable if expiry is actually
        // enforced.
        assertThat(response.statusCode()).isEqualTo(401);
    }

    // ==================================================================
    // Mass assignment
    // ==================================================================

    @Test
    @DisplayName("a 'role' field in the registration body is a 400, not a free admin account")
    void massAssignmentIsImpossible() throws Exception {
        // Sent as a raw string rather than through a DTO, because the whole
        // point is to send a field no DTO in this codebase has.
        String body = """
                {"email":"escalate@test.dev","password":"%s","displayName":"Esc","role":"ROLE_ADMIN"}
                """.formatted(PASSWORD);

        HttpResponse<String> response = send(request(null, "/api/v1/auth/register")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());

        // Two independent things have to be true for this to be safe, and both
        // are worth stating. RegisterRequest has no `role` component, so there
        // is nothing for Jackson to bind onto; and Jackson is configured with
        // fail-on-unknown-properties, so the attempt is rejected loudly rather
        // than dropped silently. The second is what protects the day somebody
        // adds a matching field to the DTO for an unrelated reason.
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(objectMapper.readTree(response.body()).path("code").asText())
                .isEqualTo("MALFORMED_REQUEST");

        assertThat(users.findByEmailIgnoreCase("escalate@test.dev"))
                .as("no account at all should have been created")
                .isEmpty();
    }

    @Test
    @DisplayName("a password under 10 characters is refused with a fieldErrors.password entry")
    void shortPasswordIsRejectedWithAFieldError() throws Exception {
        HttpResponse<String> response = post(null, "/api/v1/auth/register", Map.of(
                "email", "short@test.dev",
                "password", "abc123",
                "displayName", "Shorty"));

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode error = objectMapper.readTree(response.body());
        assertThat(error.path("code").asText()).isEqualTo("VALIDATION_FAILED");
        // Per-field messages so the form can highlight the offending input,
        // rather than a single opaque "invalid request".
        assertThat(error.path("fieldErrors").path("password").asText())
                .contains("10");
    }

    // ==================================================================
    // Role-based access
    // ==================================================================

    @Test
    @DisplayName("/actuator/prometheus is forbidden to an ordinary authenticated user")
    void metricsAreNotPublic() throws Exception {
        String userToken = mintTokenFor("alice@test.dev", "ROLE_USER");

        HttpResponse<String> response = send(request(userToken, "/actuator/prometheus").GET().build());

        // 403, not 404: unlike a booking, the existence of a metrics endpoint is
        // not a secret worth protecting - it is documented, and every Spring Boot
        // service has one. There is nothing to enumerate.
        assertThat(response.statusCode())
                .as("metrics leak traffic shape, error rates and endpoint names")
                .isEqualTo(403);
        assertThat(objectMapper.readTree(response.body()).path("code").asText())
                .isEqualTo("FORBIDDEN");
    }

    @Test
    @DisplayName("/actuator/prometheus is reachable by an admin")
    void metricsAreReachableByAnAdmin() throws Exception {
        String adminToken = mintTokenFor("admin@test.dev", "ROLE_ADMIN");

        HttpResponse<String> response = send(request(adminToken, "/actuator/prometheus").GET().build());

        // The negative test above only proves the endpoint is hard to reach.
        // Without this one, a rule that denied everybody would look identical.
        assertThat(response.statusCode()).isEqualTo(200);
    }

    // ==================================================================
    // Cross-tenant object access
    // ==================================================================

    @Test
    @DisplayName("a seat belonging to a different event cannot be held through this event")
    void seatsCannotBeBorrowedFromAnotherEvent() throws Exception {
        TestFixtures.Auditorium mine = fixtures.createAuditorium(1, 4);
        TestFixtures.Auditorium somebodyElses = fixtures.createAuditorium(1, 4);
        String alice = mintTokenFor("alice@test.dev", "ROLE_USER");

        // The seat id is real, and the event id is real; they simply do not
        // belong together. EventSeatRepository.findForEvent puts eventId in the
        // WHERE clause for exactly this reason - it is an authorization check
        // wearing the clothes of a filter. Never trust an id from a request body
        // to belong where the URL says it does.
        HttpResponse<String> response = post(alice,
                "/api/v1/events/" + mine.eventId() + "/holds",
                Map.of("seatIds", List.of(somebodyElses.firstSeat())));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(objectMapper.readTree(response.body()).path("code").asText())
                // Reported as unavailable rather than not-found on purpose: two
                // different answers would let a caller map out which seat ids
                // exist across the whole system.
                .isEqualTo("SEAT_UNAVAILABLE");

        assertThat(fixtures.activeClaimsOn(somebodyElses.firstSeat())).isZero();
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private String mintTokenFor(String email, String role) {
        long id = fixtures.createUser(email, role);
        User user = users.findById(id).orElseThrow();
        return jwtService.issueAccessToken(user);
    }

    /**
     * Build a {@link JwtService} that differs from the running one in exactly
     * one respect, so a failure can only be attributed to that difference.
     */
    private JwtService jwtServiceWith(String base64Secret, String issuer, Duration accessTtl) {
        SecurityProperties properties = new SecurityProperties();
        SecurityProperties.Jwt jwt = new SecurityProperties.Jwt();
        jwt.setSecret(base64Secret);
        jwt.setIssuer(issuer);
        jwt.setAccessTokenTtl(accessTtl);
        jwt.setRefreshTokenTtl(Duration.ofDays(1));
        properties.setJwt(jwt);
        return new JwtService(properties);
    }

    private String randomBase64Key() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private HttpResponse<String> post(String token, String path, Map<String, ?> body) throws Exception {
        return send(request(token, path)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build());
    }

    private HttpRequest.Builder request(String token, String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
