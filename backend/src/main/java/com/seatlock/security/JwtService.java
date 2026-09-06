package com.seatlock.security;

import com.seatlock.config.SecurityProperties;
import com.seatlock.domain.Role;
import com.seatlock.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Mints and verifies tokens.
 *
 * <h2>Two kinds of token, on purpose</h2>
 *
 * <table>
 *   <tr><th></th><th>Access</th><th>Refresh</th></tr>
 *   <tr><td>Form</td><td>signed JWT</td><td>256 random bits</td></tr>
 *   <tr><td>Lifetime</td><td>15 minutes</td><td>7 days</td></tr>
 *   <tr><td>Checked against DB</td><td>never</td><td>every use</td></tr>
 *   <tr><td>Revocable</td><td>no</td><td>yes</td></tr>
 * </table>
 *
 * <p>The access token is stateless so that authenticating a request costs one
 * HMAC verification and zero I/O - which is what lets the API scale horizontally
 * without a shared session store. The price is that it cannot be revoked, so its
 * lifetime <em>is</em> its blast radius, hence 15 minutes.
 *
 * <p>The refresh token is deliberately <b>not</b> a JWT. It carries no claims,
 * so there is nothing in it to trust or to get wrong; it is just a lookup key
 * into a table we control. That makes revocation, rotation and reuse detection
 * trivial - all things a self-contained token cannot do.
 *
 * <h2>The two classic JWT vulnerabilities, and where they are handled</h2>
 *
 * <ol>
 *   <li><b>{@code alg: none}.</b> Early JWT libraries would happily "verify" a
 *       token whose header claimed no algorithm. JJWT 0.12's
 *       {@code verifyWith(SecretKey)} binds verification to a MAC algorithm at
 *       the API level - an unsigned or asymmetrically-signed token is rejected
 *       before the claims are even read. This is why the parser is built with
 *       {@code verifyWith} and never with the deprecated
 *       {@code setSigningKey} + manual algorithm inspection.</li>
 *   <li><b>Weak key.</b> HS256 with a short secret is brute-forceable offline,
 *       and forging a token then costs nothing. The constructor below refuses to
 *       start the application if the key is under 256 bits.</li>
 * </ol>
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    /** RFC 7518 requires an HMAC-SHA256 key at least as long as the hash output. */
    private static final int MIN_KEY_BYTES = 32;

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_EMAIL = "email";

    private final SecretKey signingKey;
    private final String issuer;
    private final Duration accessTtl;
    private final Duration refreshTtl;
    private final SecureRandom random = new SecureRandom();

    public JwtService(SecurityProperties properties) {
        SecurityProperties.Jwt cfg = properties.getJwt();

        byte[] keyBytes;
        try {
            keyBytes = Decoders.BASE64.decode(cfg.getSecret());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "seatlock.security.jwt.secret must be Base64. Generate one with: openssl rand -base64 32", ex);
        }

        // Fail at startup, not at the first forged token. A service that boots
        // with a weak signing key is a service where anyone can mint an admin
        // session, and nothing about its behaviour would reveal that.
        if (keyBytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "JWT secret decodes to " + keyBytes.length + " bytes; HS256 requires at least "
                    + MIN_KEY_BYTES + ". Generate one with: openssl rand -base64 32");
        }

        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.issuer = cfg.getIssuer();
        this.accessTtl = cfg.getAccessTokenTtl();
        this.refreshTtl = cfg.getRefreshTokenTtl();

        log.info("JWT configured: issuer={}, accessTtl={}, refreshTtl={}", issuer, accessTtl, refreshTtl);
    }

    // ------------------------------------------------------------------
    // Access tokens
    // ------------------------------------------------------------------

    /**
     * Mint an access token for a user.
     *
     * <p>The subject is the numeric user id, not the email. Emails change; a
     * primary key does not, and a token that outlives an email change should
     * still identify the right account rather than a stale address. The email is
     * carried as a non-authoritative display claim.
     *
     * <p>The role is embedded so that authorising a request needs no database
     * lookup. That is the trade being made: an admin demoted right now keeps
     * their admin token for up to 15 minutes. For a ticketing system that is
     * acceptable; for something where instant demotion matters you would either
     * shorten the TTL or check a fast revocation list - which is a real answer
     * to give if an interviewer pushes on it, rather than pretending the
     * trade-off does not exist.
     */
    public String issueAccessToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .issuer(issuer)
                // A unique token id. Not used for revocation here, but it makes
                // two tokens issued in the same second distinguishable in logs,
                // and it is the hook a future denylist would hang off.
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .claim(CLAIM_ROLE, user.getRole().name())
                .claim(CLAIM_EMAIL, user.getEmail())
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Verify a token and extract who it belongs to.
     *
     * @return the principal, or empty if the token is missing, expired,
     *         tampered with, issued by someone else, or otherwise unusable.
     *         One empty result for every failure mode, deliberately: telling a
     *         caller <em>why</em> a token failed is telling an attacker which
     *         part of their forgery to fix next.
     */
    public Optional<AuthenticatedUser> parseAccessToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    // Binds verification to HMAC with this key. A token whose
                    // header says {"alg":"none"} or {"alg":"RS256"} cannot pass.
                    .verifyWith(signingKey)
                    // Reject anything not minted by us, even if it validates.
                    .requireIssuer(issuer)
                    // Small tolerance for clock drift between application
                    // servers. Without it, a machine 2 seconds ahead rejects
                    // tokens another machine has only just issued. Kept tight -
                    // this is added to every token's effective lifetime.
                    .clockSkewSeconds(30)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            Long userId = Long.valueOf(claims.getSubject());
            Role role = Role.valueOf(claims.get(CLAIM_ROLE, String.class));
            String email = claims.get(CLAIM_EMAIL, String.class);

            return Optional.of(new AuthenticatedUser(userId, email, role));

        } catch (JwtException | IllegalArgumentException ex) {
            // Covers expiry, bad signature, malformed structure, wrong issuer,
            // an unparseable subject, and an unknown role name. DEBUG, not WARN:
            // expired tokens are completely routine and would otherwise flood
            // the logs.
            log.debug("Rejected access token: {}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public Duration accessTokenTtl() { return accessTtl; }
    public Duration refreshTokenTtl() { return refreshTtl; }

    // ------------------------------------------------------------------
    // Refresh tokens
    // ------------------------------------------------------------------

    /**
     * Generate an opaque refresh token: 32 bytes from a CSPRNG, base64url encoded.
     *
     * <p>256 bits of entropy makes guessing hopeless, which is what lets us store
     * only a fast hash of it (see {@link #hashRefreshToken}). URL-safe encoding
     * without padding so it can travel in a header or a JSON body without
     * escaping.
     */
    public String generateRefreshToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * SHA-256, hex encoded, for storage.
     *
     * <p>A fast hash is correct <em>here</em> and would be wrong for a password.
     * Passwords are low-entropy and human-chosen, so they need a deliberately
     * slow hash (BCrypt) to make offline guessing expensive. This token is 256
     * random bits: there is no dictionary to try, so the only property we need
     * is preimage resistance, and making it slow would just add latency to every
     * refresh.
     */
    public String hashRefreshToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandated by the JLS to be present on every JVM.
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
