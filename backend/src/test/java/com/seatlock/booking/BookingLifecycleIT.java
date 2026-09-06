package com.seatlock.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatlock.domain.User;
import com.seatlock.repository.BookingRepository;
import com.seatlock.repository.UserRepository;
import com.seatlock.security.JwtService;
import com.seatlock.support.IntegrationTestBase;
import com.seatlock.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * The booking flow end to end, over real HTTP, against a real Postgres and a
 * real Redis.
 *
 * <h2>What this class is for, given the other two integration tests</h2>
 *
 * {@link ConcurrentSeatBookingIT} proves the system does not oversell under
 * contention. {@link DefenceInDepthIT} proves the database is still correct with
 * Redis taken out of the picture. Neither of them ever completes a booking.
 *
 * <p>This class walks the ordinary path - hold, pay, confirm - and then every
 * edge that hangs off it: a retried payment, a reused key, a declined card, a
 * lapsed timer, a cancellation, an early release. Those are the paths a real
 * user actually hits, and they are where the interesting coupling lives, because
 * each one has to leave <b>Redis and Postgres agreeing with each other</b> with
 * no transaction spanning the two.
 *
 * <h2>Why real HTTP rather than calling the service</h2>
 *
 * Because several of the things being asserted are not in {@code BookingService}
 * at all. The 400 for a missing {@code Idempotency-Key} comes from the
 * controller's {@code @RequestHeader(required = true)}; the 402 status comes
 * from the {@code ErrorCode} enum via the exception handler; the error envelope
 * shape comes from {@code GlobalExceptionHandler}. Calling the service directly
 * would test none of that, and the API contract in {@code docs/API.md} is
 * written in terms of status codes and JSON, not method return values.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Booking lifecycle: the happy path and its edges")
class BookingLifecycleIT extends IntegrationTestBase {

    @LocalServerPort
    private int port;

    @Autowired private TestFixtures fixtures;
    @Autowired private UserRepository users;
    @Autowired private BookingRepository bookings;
    @Autowired private JwtService jwtService;
    @Autowired private StringRedisTemplate redis;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private HoldExpiryReaper reaper;

    /**
     * A spy over the real stub, not a mock of it.
     *
     * <p>The real implementation still runs - the decline logic, the outstanding
     * authorization bookkeeping, all of it. The spy exists purely so a test can
     * see the {@code authorizationId} the gateway handed back, which is
     * otherwise internal to {@code BookingService} and is the only thing
     * {@link StubPaymentGateway#isOutstanding} can be asked about.
     */
    @SpyBean
    private StubPaymentGateway payments;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeEach
    void resetState() {
        fixtures.reset();
        // Redis is not part of the database transaction and has nothing to roll
        // back, so a hold from a previous test would survive into this one.
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    // ==================================================================
    // The happy path
    // ==================================================================

    @Test
    @DisplayName("hold then confirm produces a CONFIRMED booking, BOOKED seats, and no leftover hold")
    void holdThenConfirmCompletesTheBooking() throws Exception {
        TestFixtures.Auditorium hall = fixtures.createAuditorium(2, 5);
        long seat = hall.firstSeat();
        String alice = tokenFor("alice@test.dev");

        HttpResponse<String> held = postHold(alice, hall.eventId(), List.of(seat));
        assertThat(held.statusCode()).isEqualTo(201);

        JsonNode hold = objectMapper.readTree(held.body());
        String holdId = hold.path("holdId").asText();
        assertThat(hold.path("ttlSeconds").asLong()).isEqualTo(480);   // the configured 8m
        assertThat(hold.path("totalMinor").asLong()).isEqualTo(45_000);

        // A hold is only a reservation: the seat is claimed through booking_seats
        // but its own status is untouched, because nothing has been paid for yet.
        assertThat(fixtures.seatStatus(seat)).isEqualTo("AVAILABLE");
        assertThat(fixtures.activeClaimsOn(seat)).isEqualTo(1);

        HttpResponse<String> confirmed = confirm(alice, holdId, UUID.randomUUID().toString(),
                "CARD", "tok_demo_success");
        assertThat(confirmed.statusCode()).isEqualTo(200);

        JsonNode booking = objectMapper.readTree(confirmed.body());
        assertThat(booking.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(booking.path("reference").asText()).startsWith("SL-");
        // A confirmed booking has no expiry - there is nothing left to run out.
        assertThat(booking.path("expiresAt").isNull() || booking.path("expiresAt").isMissingNode())
                .isTrue();

        assertThat(fixtures.seatStatus(seat))
                .as("payment succeeded, so the seat is now durably sold")
                .isEqualTo("BOOKED");

        // Step 5 of the confirm sequence: hand the Redis lease back. Skipping it
        // would be harmless (the TTL clears it anyway), but during a busy drop
        // "harmless" still means eight minutes of a key nobody needs.
        assertThat(redis.keys("seatlock:hold:*"))
                .as("the reservation has done its job and should be gone")
                .isEmpty();
    }

    // ==================================================================
    // Idempotency
    // ==================================================================

    @Test
    @DisplayName("replaying an Idempotency-Key returns the same body and creates no second booking")
    void replayingAnIdempotencyKeyIsFree() throws Exception {
        TestFixtures.Auditorium hall = fixtures.createAuditorium(1, 4);
        String alice = tokenFor("alice@test.dev");
        String holdId = holdOneSeat(alice, hall.eventId(), hall.firstSeat());

        String key = UUID.randomUUID().toString();

        HttpResponse<String> first = confirm(alice, holdId, key, "CARD", "tok_demo_success");
        HttpResponse<String> replay = confirm(alice, holdId, key, "CARD", "tok_demo_success");

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(replay.statusCode()).isEqualTo(200);

        // Compared as parsed JSON rather than as raw strings: the guarantee is
        // "the same answer", not "the same bytes after a round trip through
        // Jackson". Whitespace is not part of the contract.
        assertThat(objectMapper.readTree(replay.body()))
                .as("a retry must replay the original answer, not compute a new one")
                .isEqualTo(objectMapper.readTree(first.body()));

        // The assertion that actually matters: read the table, not the response.
        // A double booking that happened to render identically would still be a
        // double booking - and a double charge.
        assertThat(bookings.count())
                .as("exactly one booking row for two identical requests")
                .isEqualTo(1);
        assertThat(fixtures.countBookingsWithStatus("CONFIRMED")).isEqualTo(1);
    }

    @Test
    @DisplayName("the same key with a different body is refused with 422 IDEMPOTENCY_KEY_REUSED")
    void reusingAKeyForADifferentRequestIsRefused() throws Exception {
        TestFixtures.Auditorium hall = fixtures.createAuditorium(1, 4);
        String alice = tokenFor("alice@test.dev");
        String holdId = holdOneSeat(alice, hall.eventId(), hall.firstSeat());

        String key = UUID.randomUUID().toString();
        assertThat(confirm(alice, holdId, key, "CARD", "tok_demo_success").statusCode()).isEqualTo(200);

        // Same key, different payload. An idempotency key is a promise that the
        // SAME request is being retried; honouring this one would either replay
        // somebody's stored response to a request they did not make, or silently
        // do something other than what was asked.
        HttpResponse<String> reused = confirm(alice, holdId, key, "UPI", "tok_demo_success");

        assertThat(reused.statusCode()).isEqualTo(422);
        assertThat(objectMapper.readTree(reused.body()).path("code").asText())
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(bookings.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a missing Idempotency-Key header is a 400, not an unprotected booking")
    void missingIdempotencyKeyIsRejected() throws Exception {
        TestFixtures.Auditorium hall = fixtures.createAuditorium(1, 4);
        String alice = tokenFor("alice@test.dev");
        String holdId = holdOneSeat(alice, hall.eventId(), hall.firstSeat());

        HttpResponse<String> response = confirm(alice, holdId, null, "CARD", "tok_demo_success");

        // required = true on the @RequestHeader. Making it optional would mean
        // the protection only applies to clients that remembered to ask for it,
        // which is exactly the set of clients that did not need reminding.
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(objectMapper.readTree(response.body()).path("code").asText())
                .isEqualTo("MALFORMED_REQUEST");
        assertThat(fixtures.countBookingsWithStatus("CONFIRMED")).isZero();
    }

    // ==================================================================
    // Payment failure
    // ==================================================================

    @Test
    @DisplayName("a declined card leaves the booking PENDING, the seats claimable, and no money reserved")
    void declinedPaymentIsFullyRecoverable() throws Exception {
        TestFixtures.Auditorium hall = fixtures.createAuditorium(1, 4);
        long seat = hall.firstSeat();
        String alice = tokenFor("alice@test.dev");
        String holdId = holdOneSeat(alice, hall.eventId(), seat);

        // Watch what the gateway hands back, without changing what it does.
        AtomicReference<PaymentGateway.PaymentResult> lastAuthorization = new AtomicReference<>();
        doAnswer(invocation -> {
            PaymentGateway.PaymentResult result =
                    (PaymentGateway.PaymentResult) invocation.callRealMethod();
            lastAuthorization.set(result);
            return result;
        }).when(payments).authorize(anyString(), anyLong(), anyString());

        HttpResponse<String> declined = confirm(alice, holdId, UUID.randomUUID().toString(),
                "CARD", "tok_demo_decline");

        assertThat(declined.statusCode()).isEqualTo(402);
        assertThat(objectMapper.readTree(declined.body()).path("code").asText())
                .isEqualTo("PAYMENT_DECLINED");

        // Nothing was committed, so the reservation survives. The customer fixes
        // their card and tries again on the same hold - which is the whole point
        // of authorising before committing rather than committing on trust.
        assertThat(fixtures.countBookingsWithStatus("PENDING")).isEqualTo(1);
        assertThat(fixtures.activeClaimsOn(seat)).isEqualTo(1);
        assertThat(fixtures.seatStatus(seat)).isEqualTo("AVAILABLE");

        // The gateway declined before it recorded anything, so there is no
        // authorization id in existence and therefore nothing reserved on the
        // customer's card. (isOutstanding cannot be asked about a null id, and
        // the absence of an id is the stronger statement anyway.)
        assertThat(lastAuthorization.get().approved()).isFalse();
        assertThat(lastAuthorization.get().authorizationId())
                .as("a decline must not leave a dangling authorization")
                .isNull();

        // ---- and the seats really are still this user's to buy --------------
        HttpResponse<String> retry = confirm(alice, holdId, UUID.randomUUID().toString(),
                "CARD", "tok_demo_success");
        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(fixtures.seatStatus(seat)).isEqualTo("BOOKED");

        // The successful attempt DID reserve money - and captured it immediately
        // after the booking committed, so the gateway is holding nothing.
        String authorizationId = lastAuthorization.get().authorizationId();
        assertThat(authorizationId).isNotNull();
        assertThat(payments.isOutstanding(authorizationId))
                .as("authorize -> commit -> capture leaves no outstanding reservation")
                .isFalse();
    }

    // ==================================================================
    // Expiry
    // ==================================================================

    @Test
    @DisplayName("confirming an expired hold is refused with 409 HOLD_EXPIRED")
    void confirmingAnExpiredHoldIsRefused() throws Exception {
        TestFixtures.Auditorium hall = fixtures.createAuditorium(1, 4);
        String alice = tokenFor("alice@test.dev");
        String holdId = holdOneSeat(alice, hall.eventId(), hall.firstSeat());

        // Back-date the persisted expiry rather than sleeping for eight minutes.
        // Note this leaves Redis's own lease alive, which is deliberate: it
        // proves the booking row's expiry is checked in its own right and not
        // merely inferred from Redis being empty.
        long bookingId = bookings.findByPublicId(UUID.fromString(holdId)).orElseThrow().getId();
        fixtures.backdateHoldExpiry(bookingId);

        HttpResponse<String> response = confirm(alice, holdId, UUID.randomUUID().toString(),
                "CARD", "tok_demo_success");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(objectMapper.readTree(response.body()).path("code").asText())
                .isEqualTo("HOLD_EXPIRED");
        assertThat(fixtures.countBookingsWithStatus("CONFIRMED")).isZero();
    }

    @Test
    @DisplayName("the reaper turns lapsed pending bookings into EXPIRED and frees their seats")
    void theReaperExpiresLapsedHolds() throws Exception {
        TestFixtures.Auditorium hall = fixtures.createAuditorium(1, 4);
        long seat = hall.firstSeat();
        String alice = tokenFor("alice@test.dev");
        String holdId = holdOneSeat(alice, hall.eventId(), seat);

        long bookingId = bookings.findByPublicId(UUID.fromString(holdId)).orElseThrow().getId();
        fixtures.backdateHoldExpiry(bookingId);

        // Invoked directly rather than waited for. The scheduled delay is set to
        // an hour in the test profile precisely so the background job cannot
        // wander into the middle of another test's timing; a test that needs it
        // asks for it.
        reaper.expireLapsedHolds();

        assertThat(fixtures.countBookingsWithStatus("EXPIRED"))
                .as("the abandoned reservation should no longer look live in 'my bookings'")
                .isEqualTo(1);
        assertThat(fixtures.activeClaimsOn(seat))
                .as("the database trigger deactivated the seat claim on the status change")
                .isZero();

        // In production Redis expires first and this job tidies up afterwards, so
        // clearing the lease here reproduces the real ordering rather than
        // inventing a state that never occurs.
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();

        String bob = tokenFor("bob@test.dev");
        assertThat(postHold(bob, hall.eventId(), List.of(seat)).statusCode())
                .as("the seat must be genuinely back on sale")
                .isEqualTo(201);
    }

    // ==================================================================
    // Cancel, extend, release
    // ==================================================================

    @Test
    @DisplayName("cancelling a confirmed booking returns its seats to sale")
    void cancellingAConfirmedBookingReturnsTheSeats() throws Exception {
        TestFixtures.Auditorium hall = fixtures.createAuditorium(1, 4);
        long seat = hall.firstSeat();
        String alice = tokenFor("alice@test.dev");
        String holdId = holdOneSeat(alice, hall.eventId(), seat);

        assertThat(confirm(alice, holdId, UUID.randomUUID().toString(), "CARD", "tok_demo_success")
                .statusCode()).isEqualTo(200);
        assertThat(fixtures.seatStatus(seat)).isEqualTo("BOOKED");

        HttpResponse<String> cancelled = send(request(alice, "/bookings/" + holdId + "/cancel")
                .POST(HttpRequest.BodyPublishers.noBody()).build());

        assertThat(cancelled.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(cancelled.body()).path("status").asText()).isEqualTo("CANCELLED");

        assertThat(fixtures.seatStatus(seat))
                .as("EventSeat.release() puts a sold seat back on the market")
                .isEqualTo("AVAILABLE");
        assertThat(fixtures.activeClaimsOn(seat))
                .as("the trigger dropped the booking_seats row out of the partial unique index")
                .isZero();

        // The real proof that "returned to sale" means something: somebody else
        // can now buy it.
        String bob = tokenFor("bob@test.dev");
        assertThat(postHold(bob, hall.eventId(), List.of(seat)).statusCode()).isEqualTo(201);
    }

    /**
     * <p>Worth knowing about this endpoint: the extension is measured
     * <em>from now</em>, not added to whatever is left. Calling it one second
     * after creating an 8-minute hold would replace 8 minutes with 3. That is
     * why the UI only offers it in the final minute, and why this test first
     * shortens the lease to 30 seconds - so that "extend" genuinely means "more
     * time" rather than accidentally testing a reduction.
     */
    @Test
    @DisplayName("extend applies the configured grace period to the live lease")
    void extendGrantsMoreTime() throws Exception {
        TestFixtures.Auditorium hall = fixtures.createAuditorium(1, 4);
        String alice = tokenFor("alice@test.dev");
        String holdId = holdOneSeat(alice, hall.eventId(), hall.firstSeat());

        Set<String> keys = redis.keys("seatlock:hold:*");
        assertThat(keys).hasSize(1);
        String holdKey = keys.iterator().next();

        // Stand in for seven and a half minutes of the user typing card details.
        redis.expire(holdKey, Duration.ofSeconds(30));
        assertThat(redis.getExpire(holdKey)).isLessThanOrEqualTo(30);

        HttpResponse<String> extended = send(request(alice, "/holds/" + holdId + "/extend")
                .POST(HttpRequest.BodyPublishers.noBody()).build());

        assertThat(extended.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(extended.body());
        assertThat(body.path("ttlSeconds").asLong()).isEqualTo(180);   // the configured 3m
        assertThat(Instant.parse(body.path("expiresAt").asText()))
                .isAfter(Instant.now().plusSeconds(120));

        assertThat(redis.getExpire(holdKey))
                .as("Redis is the authority on when the seat is actually released")
                .isGreaterThan(120);
    }

    @Test
    @DisplayName("releasing a hold early frees the seat for another user immediately")
    void releasingAHoldEarlyFreesTheSeat() throws Exception {
        TestFixtures.Auditorium hall = fixtures.createAuditorium(1, 4);
        long seat = hall.firstSeat();
        String alice = tokenFor("alice@test.dev");
        String bob = tokenFor("bob@test.dev");

        String holdId = holdOneSeat(alice, hall.eventId(), seat);

        // Bob cannot have it yet.
        assertThat(postHold(bob, hall.eventId(), List.of(seat)).statusCode()).isEqualTo(409);

        HttpResponse<String> released = send(request(alice, "/holds/" + holdId).DELETE().build());
        assertThat(released.statusCode()).isEqualTo(204);

        // Waiting out the TTL would work too, but during a busy drop that is
        // eight minutes in which nobody can buy a seat nobody wants.
        assertThat(redis.keys("seatlock:hold:*")).isEmpty();
        assertThat(fixtures.activeClaimsOn(seat)).isZero();
        assertThat(fixtures.countBookingsWithStatus("CANCELLED")).isEqualTo(1);

        assertThat(postHold(bob, hall.eventId(), List.of(seat)).statusCode()).isEqualTo(201);
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    /**
     * Create a user and sign a real access token for them.
     *
     * <p>Minted directly rather than through {@code /auth/login}, which would
     * spend a round of cost-12 BCrypt (~250ms) per user to exercise a code path
     * {@code SecurityIT} already covers properly. The token is produced by the
     * application's own {@code JwtService} and verified by the real filter
     * chain, so authentication is still genuinely part of every request here.
     */
    private String tokenFor(String email) {
        long id = fixtures.createUser(email);
        User user = users.findById(id).orElseThrow();
        return jwtService.issueAccessToken(user);
    }

    private String holdOneSeat(String token, long eventId, long seatId) throws Exception {
        HttpResponse<String> response = postHold(token, eventId, List.of(seatId));
        assertThat(response.statusCode())
                .as("fixture setup: the hold this test builds on must succeed")
                .isEqualTo(201);
        return objectMapper.readTree(response.body()).path("holdId").asText();
    }

    private HttpResponse<String> postHold(String token, long eventId, List<Long> seatIds) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("seatIds", seatIds));
        return send(request(token, "/events/" + eventId + "/holds")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private HttpResponse<String> confirm(String token,
                                         String holdId,
                                         String idempotencyKey,
                                         String paymentMethod,
                                         String paymentToken) throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("paymentMethod", paymentMethod, "paymentToken", paymentToken));
        HttpRequest.Builder builder = request(token, "/bookings/" + holdId + "/confirm");
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        return send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private HttpRequest.Builder request(String token, String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1" + path))
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
