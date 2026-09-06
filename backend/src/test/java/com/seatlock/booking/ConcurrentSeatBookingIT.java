package com.seatlock.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatlock.domain.User;
import com.seatlock.repository.UserRepository;
import com.seatlock.security.JwtService;
import com.seatlock.support.IntegrationTestBase;
import com.seatlock.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h1>The test this whole project exists to pass.</h1>
 *
 * Fifty real HTTP requests, from fifty different authenticated users, fired at
 * <b>the same single seat</b> at the same instant. Exactly one must succeed.
 *
 * <h2>Why a {@link CyclicBarrier} and not just fifty threads</h2>
 *
 * Submitting fifty tasks to a pool does <em>not</em> make them simultaneous. The
 * pool starts them as threads become available, and the first one is often
 * finished before the fiftieth has begun - so the test would pass without ever
 * creating the race it claims to test. That is the single most common way a
 * "concurrency test" turns out to be testing nothing.
 *
 * <p>A barrier fixes it. Every thread does its setup, then blocks on
 * {@code barrier.await()}. Nobody proceeds until all fifty have arrived, at
 * which point the barrier releases them together and they hit the endpoint
 * inside the same few hundred microseconds. The thread pool is sized to 50 so
 * that all fifty can actually be waiting at once - a smaller pool would deadlock
 * on the barrier, which is itself a useful thing to understand.
 *
 * <h2>What is being asserted</h2>
 *
 * <ol>
 *   <li>Exactly one 201 Created.</li>
 *   <li>Exactly forty-nine 409 Conflict, all with code {@code SEAT_UNAVAILABLE} -
 *       clean, specific rejections, not 500s. Losing a race is a normal outcome
 *       and must look like one.</li>
 *   <li><b>Exactly one active claim on the seat in the database.</b> This is the
 *       real oversell assertion: it reads the table directly rather than
 *       trusting the API's own account of what it did.</li>
 *   <li>Exactly one hold key in Redis.</li>
 * </ol>
 *
 * <p>Run as {@code @RepeatedTest} because a concurrency bug that shows up one
 * time in twenty is still a concurrency bug, and a single green run proves very
 * little about a race.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Concurrent booking: exactly-once under contention")
class ConcurrentSeatBookingIT extends IntegrationTestBase {

    private static final int CONTENDERS = 50;

    @LocalServerPort
    private int port;

    @Autowired private TestFixtures fixtures;
    @Autowired private UserRepository users;
    @Autowired private JwtService jwtService;
    @Autowired private StringRedisTemplate redis;
    @Autowired private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeEach
    void resetState() {
        fixtures.reset();
        // Holds outlive a transaction rollback because Redis has no transactions
        // to roll back. Left behind, they would make the next test look like it
        // lost a race to a ghost.
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @RepeatedTest(value = 5, name = "run {currentRepetition} of {totalRepetitions}")
    @DisplayName("50 simultaneous requests for one seat produce exactly one booking")
    void exactlyOneWinnerUnderMaximumContention() throws Exception {

        TestFixtures.Auditorium auditorium = fixtures.createAuditorium(5, 10);
        long contestedSeat = auditorium.firstSeat();

        List<String> tokens = mintTokensFor(CONTENDERS);

        // ---- Line everybody up at the start line -------------------------
        CyclicBarrier startLine = new CyclicBarrier(CONTENDERS);
        ExecutorService pool = Executors.newFixedThreadPool(CONTENDERS);
        // Counts down as each request COMPLETES, so the main thread can wait for
        // all of them without polling.
        CountDownLatch finished = new CountDownLatch(CONTENDERS);

        List<HttpResponse<String>> responses = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicInteger unexpectedErrors = new AtomicInteger();

        String body = objectMapper.writeValueAsString(Map.of("seatIds", List.of(contestedSeat)));

        for (int i = 0; i < CONTENDERS; i++) {
            String token = tokens.get(i);
            pool.submit(() -> {
                try {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port
                                    + "/api/v1/events/" + auditorium.eventId() + "/holds"))
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + token)
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();

                    // Everything above is per-thread setup. Nobody sends until
                    // everybody is ready.
                    startLine.await(20, TimeUnit.SECONDS);

                    responses.add(http.send(request, HttpResponse.BodyHandlers.ofString()));

                } catch (Exception ex) {
                    unexpectedErrors.incrementAndGet();
                } finally {
                    finished.countDown();
                }
            });
        }

        assertThat(finished.await(60, TimeUnit.SECONDS))
                .as("all %d requests should complete within 60s", CONTENDERS)
                .isTrue();
        pool.shutdownNow();

        // ---- Assert on the HTTP outcomes ---------------------------------
        assertThat(unexpectedErrors.get())
                .as("no request should fail at the transport level")
                .isZero();
        assertThat(responses).hasSize(CONTENDERS);

        List<HttpResponse<String>> created = responses.stream()
                .filter(r -> r.statusCode() == 201).toList();
        List<HttpResponse<String>> conflicts = responses.stream()
                .filter(r -> r.statusCode() == 409).toList();

        assertThat(created)
                .as("exactly one request may win the seat")
                .hasSize(1);

        assertThat(conflicts)
                .as("every other request must lose cleanly with 409, not 500")
                .hasSize(CONTENDERS - 1);

        for (HttpResponse<String> conflict : conflicts) {
            JsonNode error = objectMapper.readTree(conflict.body());
            assertThat(error.path("code").asText())
                    .as("losers get a specific, actionable error code")
                    .isEqualTo("SEAT_UNAVAILABLE");
            assertThat(error.path("details").path("unavailableSeatIds").isArray())
                    .as("the response names exactly which seat was lost")
                    .isTrue();
        }

        // ---- Assert on the DATABASE, not on what the API said ------------
        // This is the assertion that actually proves no oversell. The API could
        // in principle report one success while having written two rows; only
        // reading the table settles it.
        assertThat(fixtures.activeClaimsOn(contestedSeat))
                .as("the database must hold exactly one active claim on the seat")
                .isEqualTo(1);

        // ---- Assert on Redis ---------------------------------------------
        var holdKeys = redis.keys("seatlock:hold:*");
        assertThat(holdKeys)
                .as("exactly one seat hold should exist in Redis")
                .hasSize(1);
    }

    @Test
    @DisplayName("Holds are all-or-nothing: a partly-taken selection reserves nothing")
    void multiSeatHoldIsAtomic() throws Exception {
        TestFixtures.Auditorium auditorium = fixtures.createAuditorium(2, 6);
        List<Long> seats = auditorium.eventSeatIds();

        Long seatA = seats.get(0);
        Long seatB = seats.get(1);
        Long seatC = seats.get(2);
        Long seatD = seats.get(3);

        String alice = mintTokensFor(1).get(0);
        String bob = mintTokensFor(1, "bob").get(0);

        // Alice takes seat C out of the middle of Bob's intended selection.
        HttpResponse<String> aliceHold = postHold(alice, auditorium.eventId(), List.of(seatC));
        assertThat(aliceHold.statusCode()).isEqualTo(201);

        // Bob asks for A, B, C, D. C is gone, so he must get NONE of them.
        HttpResponse<String> bobHold = postHold(bob, auditorium.eventId(), List.of(seatA, seatB, seatC, seatD));
        assertThat(bobHold.statusCode()).isEqualTo(409);

        JsonNode error = objectMapper.readTree(bobHold.body());
        assertThat(error.path("code").asText()).isEqualTo("SEAT_UNAVAILABLE");
        assertThat(error.path("details").path("unavailableSeatIds").get(0).asLong())
                .as("the response tells Bob exactly which seat he lost")
                .isEqualTo(seatC);

        // The crucial part: A, B and D must still be free. A partial hold would
        // have taken three seats off sale for a booking that cannot complete.
        assertThat(fixtures.activeClaimsOn(seatA)).as("seat A must not be claimed").isZero();
        assertThat(fixtures.activeClaimsOn(seatB)).as("seat B must not be claimed").isZero();
        assertThat(fixtures.activeClaimsOn(seatD)).as("seat D must not be claimed").isZero();

        var holdKeys = redis.keys("seatlock:hold:*");
        assertThat(holdKeys)
                .as("only Alice's single hold should exist")
                .hasSize(1);
    }

    // ------------------------------------------------------------------

    private HttpResponse<String> postHold(String token, long eventId, List<Long> seatIds) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("seatIds", seatIds));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/events/" + eventId + "/holds"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private List<String> mintTokensFor(int count) {
        return mintTokensFor(count, "contender");
    }

    /**
     * Create users and sign real access tokens for them.
     *
     * <p>Tokens are minted directly rather than obtained by calling
     * {@code /auth/login} fifty times. Logging in would spend fifty rounds of
     * cost-12 BCrypt - about twelve seconds - to test something this test is not
     * about. The tokens are produced by the same {@code JwtService} the
     * application uses and are verified by the real filter chain, so the
     * authentication path is still genuinely exercised.
     */
    private List<String> mintTokensFor(int count, String prefix) {
        List<String> tokens = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String email = prefix + "-" + System.nanoTime() + "-" + i + "@test.dev";
            long id = fixtures.createUser(email);
            User user = users.findById(id).orElseThrow();
            tokens.add(jwtService.issueAccessToken(user));
        }
        return tokens;
    }
}
