package com.seatlock.security;

import com.seatlock.config.SecurityProperties;
import com.seatlock.domain.Role;
import com.seatlock.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JwtService} on its own - no Spring context, no Docker, no database.
 *
 * <h2>Why this one is a unit test when almost everything else is an IT</h2>
 *
 * Everything {@code JwtService} does is a pure function of its configuration and
 * its input: sign, verify, hash. There is nothing to integrate with, so a
 * Testcontainers boot would add four seconds per run and prove nothing extra.
 * It runs under Surefire in {@code mvn test}, which keeps the inner loop fast -
 * and this is exactly the class you want re-verified on every save, because a
 * mistake in it is not a bug, it is an authentication bypass.
 *
 * <p>The service is constructed directly from a hand-built
 * {@link SecurityProperties}, which is also what makes the negative cases
 * possible: a wrong key, a wrong issuer and an already-elapsed lifetime are all
 * just different constructor arguments here, where in a running application they
 * would be impossible to arrange.
 */
@DisplayName("JwtService: signing, verification and refresh-token hashing")
class JwtServiceTest {

    private static final String ISSUER = "seatlock-test";

    /** Two distinct, valid 256-bit keys. Only their contents differ. */
    private static final String KEY_A = keyOf((byte) 0x11);
    private static final String KEY_B = keyOf((byte) 0x22);

    // ==================================================================
    // Access tokens
    // ==================================================================

    @Test
    @DisplayName("a round-tripped token yields the right id, email and role")
    void roundTripPreservesThePrincipal() {
        JwtService service = serviceWith(KEY_A, ISSUER, Duration.ofMinutes(15));
        User alice = user(7L, "alice@test.dev", Role.ROLE_ADMIN);

        Optional<AuthenticatedUser> parsed = service.parseAccessToken(service.issueAccessToken(alice));

        assertThat(parsed).isPresent();
        AuthenticatedUser principal = parsed.orElseThrow();

        // The subject is the numeric id, not the email: emails change, primary
        // keys do not, and a token that outlives an address change must still
        // identify the right account rather than a stale string.
        assertThat(principal.id()).isEqualTo(7L);
        assertThat(principal.email()).isEqualTo("alice@test.dev");

        // The role travels inside the token so that authorising a request costs
        // no database round trip. The price is that a demoted admin keeps their
        // authority until the token expires - which is why the TTL is 15 minutes.
        assertThat(principal.role()).isEqualTo(Role.ROLE_ADMIN);
        assertThat(principal.isAdmin()).isTrue();
    }

    @Test
    @DisplayName("a token signed with a different key does not parse")
    void aForeignSignatureIsRejected() {
        JwtService ours = serviceWith(KEY_A, ISSUER, Duration.ofMinutes(15));
        JwtService theirs = serviceWith(KEY_B, ISSUER, Duration.ofMinutes(15));

        String forged = theirs.issueAccessToken(user(7L, "attacker@test.dev", Role.ROLE_ADMIN));

        // Identical structure, identical claims, identical algorithm. The only
        // thing that makes the token worthless is that it was signed with a key
        // we do not hold - which is the entire security model of HS256.
        assertThat(ours.parseAccessToken(forged)).isEmpty();
    }

    @Test
    @DisplayName("an expired token does not parse")
    void anExpiredTokenIsRejected() {
        // A negative lifetime back-dates the exp claim, producing exactly the
        // state a token reaches after its fifteen minutes are up - without the
        // test having to wait. Five minutes into the past, comfortably clear of
        // the parser's 30-second clock-skew tolerance, so this cannot flake.
        JwtService issuer = serviceWith(KEY_A, ISSUER, Duration.ofMinutes(-5));
        JwtService verifier = serviceWith(KEY_A, ISSUER, Duration.ofMinutes(15));

        String stale = issuer.issueAccessToken(user(7L, "alice@test.dev", Role.ROLE_USER));

        assertThat(verifier.parseAccessToken(stale)).isEmpty();
    }

    @Test
    @DisplayName("a token from another issuer does not parse, even with the right key")
    void aTokenFromAnotherIssuerIsRejected() {
        JwtService ours = serviceWith(KEY_A, ISSUER, Duration.ofMinutes(15));
        JwtService sibling = serviceWith(KEY_A, "some-other-service", Duration.ofMinutes(15));

        String theirToken = sibling.issueAccessToken(user(7L, "alice@test.dev", Role.ROLE_USER));

        // Same key, so the signature verifies. The issuer check is what stops a
        // token minted by a sibling service - one that happens to share the
        // signing secret - from being accepted here as a login.
        assertThat(ours.parseAccessToken(theirToken)).isEmpty();
    }

    @Test
    @DisplayName("malformed input returns empty rather than throwing")
    void garbageInputIsRejectedQuietly() {
        JwtService service = serviceWith(KEY_A, ISSUER, Duration.ofMinutes(15));

        // Every one of these is a plausible thing to arrive in an Authorization
        // header, and none of them may escape as an exception: the filter calls
        // this on every request, and a throw would turn a bad token on a PUBLIC
        // endpoint into a 500.
        assertThat(service.parseAccessToken("not-a-jwt")).isEmpty();
        assertThat(service.parseAccessToken("aaa.bbb.ccc")).isEmpty();
        assertThat(service.parseAccessToken("eyJhbGciOiJub25lIn0.eyJzdWIiOiIxIn0.")).isEmpty();
        assertThat(service.parseAccessToken("....")).isEmpty();
    }

    @Test
    @DisplayName("null and blank input return empty rather than throwing")
    void nullAndBlankAreRejectedQuietly() {
        JwtService service = serviceWith(KEY_A, ISSUER, Duration.ofMinutes(15));

        assertThat(service.parseAccessToken(null)).isEmpty();
        assertThat(service.parseAccessToken("")).isEmpty();
        assertThat(service.parseAccessToken("   ")).isEmpty();
    }

    // ==================================================================
    // The startup guard
    // ==================================================================

    /**
     * <p>This is a <b>startup-time</b> guard, and that is the interesting part.
     * HS256 with a short secret is brute-forceable offline: an attacker takes any
     * token the service issued, grinds candidate keys against its signature until
     * one matches, and can then mint a token claiming {@code role: ROLE_ADMIN}.
     * Nothing about the service's behaviour would look wrong while that was
     * happening.
     *
     * <p>So the check refuses to let the application boot at all, rather than
     * failing later on some specific request. A service that starts with a weak
     * signing key is already compromised; the only safe moment to notice is
     * before it accepts its first connection.
     */
    @Test
    @DisplayName("constructing with a secret shorter than 32 bytes fails at startup")
    void aShortSecretIsRefusedAtConstruction() {
        String tooShort = Base64.getEncoder().encodeToString(new byte[16]);   // 128 bits

        assertThatThrownBy(() -> serviceWith(tooShort, ISSUER, Duration.ofMinutes(15)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("16")     // what it got
                .hasMessageContaining("32")     // what RFC 7518 requires
                .hasMessageContaining("bytes");
    }

    @Test
    @DisplayName("exactly 32 bytes is accepted - the boundary is inclusive")
    void thirtyTwoBytesIsEnough() {
        // Worth pinning: an off-by-one that made the guard `<=` would reject
        // every correctly-generated `openssl rand -base64 32` key and send the
        // next person hunting for a bug in their configuration.
        assertThat(serviceWith(Base64.getEncoder().encodeToString(new byte[32]),
                ISSUER, Duration.ofMinutes(15)))
                .isNotNull();
    }

    // ==================================================================
    // Refresh tokens
    // ==================================================================

    @Test
    @DisplayName("hashRefreshToken is deterministic")
    void refreshTokenHashIsDeterministic() {
        JwtService service = serviceWith(KEY_A, ISSUER, Duration.ofMinutes(15));

        // It has to be: the stored hash is the lookup key. A salted or otherwise
        // non-deterministic hash - which is exactly what you want for a password
        // - would make every refresh token unfindable.
        assertThat(service.hashRefreshToken("some-opaque-token"))
                .isEqualTo(service.hashRefreshToken("some-opaque-token"));
    }

    @Test
    @DisplayName("hashRefreshToken produces 64 lowercase hex characters")
    void refreshTokenHashIsSixtyFourHexCharacters() {
        JwtService service = serviceWith(KEY_A, ISSUER, Duration.ofMinutes(15));

        String hash = service.hashRefreshToken("some-opaque-token");

        // SHA-256 is 32 bytes, hex-encoded to 64 characters - which is exactly
        // what the schema declares (CHAR(64)). A mismatch here would show up as
        // a truncation at insert time, not as a test failure, which is why it is
        // worth asserting the width explicitly.
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("different inputs hash to different values")
    void differentTokensHashDifferently() {
        JwtService service = serviceWith(KEY_A, ISSUER, Duration.ofMinutes(15));

        assertThat(service.hashRefreshToken("token-a"))
                .isNotEqualTo(service.hashRefreshToken("token-b"));
        // One character apart, to check nothing is being truncated before hashing.
        assertThat(service.hashRefreshToken("token-a"))
                .isNotEqualTo(service.hashRefreshToken("token-A"));
    }

    @Test
    @DisplayName("generated refresh tokens are unique and URL-safe")
    void generatedRefreshTokensAreUniqueAndUrlSafe() {
        JwtService service = serviceWith(KEY_A, ISSUER, Duration.ofMinutes(15));

        String first = service.generateRefreshToken();
        String second = service.generateRefreshToken();

        assertThat(first).isNotEqualTo(second);
        // 256 bits of entropy, base64url with no padding, so it can travel in a
        // header or a JSON body without escaping. The high entropy is precisely
        // what licenses the fast SHA-256 above: there is no dictionary to try.
        assertThat(first).matches("[A-Za-z0-9_-]{43}");
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private JwtService serviceWith(String base64Secret, String issuer, Duration accessTtl) {
        SecurityProperties properties = new SecurityProperties();
        SecurityProperties.Jwt jwt = new SecurityProperties.Jwt();
        jwt.setSecret(base64Secret);
        jwt.setIssuer(issuer);
        jwt.setAccessTokenTtl(accessTtl);
        jwt.setRefreshTokenTtl(Duration.ofDays(7));
        properties.setJwt(jwt);
        return new JwtService(properties);
    }

    /**
     * A {@link User} with an id, which the entity has no way to be given.
     *
     * <p>{@code id} is assigned by the database and has no setter on purpose -
     * application code must never choose a primary key. Reflection is the right
     * escape hatch for a test: the alternative would be to add a setter to
     * production code purely so this file could call it, which weakens the
     * entity to suit the test suite rather than the other way round.
     */
    private User user(long id, String email, Role role) {
        User user = new User(email, "irrelevant-hash", "Test User", role);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static String keyOf(byte fill) {
        byte[] key = new byte[32];
        Arrays.fill(key, fill);
        return Base64.getEncoder().encodeToString(key);
    }
}
