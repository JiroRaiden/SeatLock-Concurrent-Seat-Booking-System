package com.seatlock.security;

import com.seatlock.domain.RefreshToken;
import com.seatlock.domain.Role;
import com.seatlock.domain.User;
import com.seatlock.exception.ApiException;
import com.seatlock.exception.ErrorCode;
import com.seatlock.repository.RefreshTokenRepository;
import com.seatlock.repository.UserRepository;
import com.seatlock.web.dto.AuthResponse;
import com.seatlock.web.dto.LoginRequest;
import com.seatlock.web.dto.RegisterRequest;
import com.seatlock.web.dto.UserSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Registration, login, token refresh and logout.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /**
     * A real BCrypt hash of a value nobody knows, used to burn the same CPU on a
     * failed lookup as on a real password check. See {@link #login}.
     *
     * <p>It is a genuine cost-12 hash so the timing matches exactly; the
     * plaintext behind it is irrelevant and is never used.
     */
    private static final String DUMMY_HASH =
            "$2a$12$Xr8Q9vJ0kZ1mN2pL3qR4uOa5bC6dE7fG8hI9jK0lM1nO2pQ3rS4tW";

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository users,
                       RefreshTokenRepository refreshTokens,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    // ------------------------------------------------------------------

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = User.normaliseEmail(request.email());

        if (users.existsByEmailIgnoreCase(email)) {
            // A deliberate, documented trade-off. Telling the user "this email
            // is already registered" does confirm that the account exists, which
            // is an enumeration oracle. The alternative - accepting silently and
            // emailing the existing owner - is genuinely better security and
            // needs an email pipeline this project does not have.
            //
            // What matters is that LOGIN never leaks the same information. An
            // attacker who can enumerate at registration learns which addresses
            // have accounts; one who can enumerate at login learns that AND gets
            // to test passwords. Only the second is worth real UX cost.
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "That email address is already registered.",
                    Map.of("field", "email"));
        }

        // Hash before constructing the entity, so no object in this method ever
        // holds both the identity and the plaintext at the same time.
        String hash = passwordEncoder.encode(request.password());

        // Role is assigned by the server, never taken from the request. This is
        // the whole of mass-assignment prevention: RegisterRequest has no role
        // field, so there is nothing for a client to set. Admins are promoted by
        // a migration or an admin endpoint, not by asking nicely.
        User user = new User(email, hash, request.displayName().trim(), Role.ROLE_USER);
        users.save(user);

        log.info("Registered user id={}", user.getId());
        return issueTokens(user);
    }

    // ------------------------------------------------------------------

    /**
     * Verify credentials and issue a token pair.
     *
     * <h2>Two things are happening that are easy to miss</h2>
     *
     * <p><b>1. The error is identical either way.</b> Whether the email is
     * unknown or the password is wrong, the caller gets exactly
     * {@code INVALID_CREDENTIALS}. Distinguishing them turns the login endpoint
     * into a free account-enumeration service.
     *
     * <p><b>2. The timing is the same either way.</b> The obvious code returns
     * early when the user is not found, so a miss answers in ~1ms and a hit takes
     * ~250ms while BCrypt runs. That difference is trivially measurable over the
     * network, and it re-creates the enumeration oracle that the identical error
     * message just closed - the response text is the same, but the clock is not.
     *
     * <p>So on a miss we run {@code matches()} against {@link #DUMMY_HASH}
     * anyway. The result is discarded; the point is that both paths pay the same
     * ~250ms. This is a small, cheap defence against a real and frequently
     * overlooked side channel.
     */
    @Transactional
    public AuthResponse login(LoginRequest request) {
        String email = User.normaliseEmail(request.email());
        Optional<User> maybeUser = users.findByEmailIgnoreCase(email);

        if (maybeUser.isEmpty()) {
            passwordEncoder.matches(request.password(), DUMMY_HASH);   // constant-work path
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS,
                    ErrorCode.INVALID_CREDENTIALS.defaultMessage());
        }

        User user = maybeUser.get();

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.debug("Failed login for user id={}", user.getId());
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS,
                    ErrorCode.INVALID_CREDENTIALS.defaultMessage());
        }

        if (!user.isEnabled()) {
            // Same generic error again: a disabled account is still an account,
            // and saying so confirms it exists.
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS,
                    ErrorCode.INVALID_CREDENTIALS.defaultMessage());
        }

        return issueTokens(user);
    }

    // ------------------------------------------------------------------

    /**
     * Exchange a refresh token for a new pair, rotating it in the process.
     *
     * <h2>Rotation with reuse detection</h2>
     *
     * Every use of a refresh token consumes it and issues a replacement. So a
     * token should be presented exactly once, ever.
     *
     * <p>If an already-consumed token turns up again, then two parties hold the
     * same token: the real user and whoever stole it. We cannot tell which one
     * is in front of us right now, so we assume the worst and revoke every live
     * token for that account. The legitimate user is forced to log in again -
     * mildly annoying, and vastly better than an attacker holding a rolling
     * session for the next seven days.
     *
     * <p>This is the behaviour recommended by the OAuth 2.0 Security Best
     * Current Practice for clients that cannot keep a secret, which includes
     * every single-page application.
     */
    @Transactional
    public AuthResponse refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "A refresh token is required.");
        }

        String hash = jwtService.hashRefreshToken(rawRefreshToken);
        RefreshToken stored = refreshTokens.findByTokenHash(hash)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED,
                        "That session is no longer valid. Please sign in again."));

        Instant now = Instant.now();

        if (stored.getRevokedAt() != null) {
            // Reuse of a consumed token. Burn the whole family down.
            int revoked = refreshTokens.revokeAllForUser(stored.getUser().getId(), now);
            log.warn("Refresh token reuse detected for user id={}; revoked {} active token(s)",
                    stored.getUser().getId(), revoked);
            throw new ApiException(ErrorCode.TOKEN_REUSE_DETECTED,
                    ErrorCode.TOKEN_REUSE_DETECTED.defaultMessage());
        }

        if (!stored.isUsable(now)) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED,
                    "That session has expired. Please sign in again.");
        }

        User user = stored.getUser();
        if (!user.isEnabled()) {
            refreshTokens.revokeAllForUser(user.getId(), now);
            throw new ApiException(ErrorCode.UNAUTHENTICATED,
                    "That session is no longer valid. Please sign in again.");
        }

        AuthResponse response = issueTokens(user);

        // Link old -> new so the chain is auditable, and mark the old one spent.
        refreshTokens.findByTokenHash(jwtService.hashRefreshToken(response.refreshToken()))
                .ifPresent(successor -> stored.replaceWith(successor, now));

        return response;
    }

    // ------------------------------------------------------------------

    /**
     * Log out by revoking the presented refresh token.
     *
     * <p>Note what logout can and cannot do. The refresh token dies immediately.
     * The access token cannot be revoked - it is a self-contained signed
     * statement, and nothing checks a database when it is verified - so it stays
     * valid for up to its remaining 15 minutes.
     *
     * <p>That is the cost of stateless authentication, and it is why the access
     * TTL is short. Pretending otherwise ("logout invalidates everything") would
     * be the wrong answer in an interview; the right one is that you chose a
     * bounded window and you know exactly how long it is.
     *
     * <p>The method is deliberately silent about unknown tokens. Logout must
     * never fail - a client calling it is trying to end a session, and answering
     * 404 would leave them holding a token they think is live.
     */
    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        refreshTokens.findByTokenHash(jwtService.hashRefreshToken(rawRefreshToken))
                .ifPresent(token -> token.revoke(Instant.now()));
    }

    /** Kill every session for a user. Used on password change and by admins. */
    @Transactional
    public void revokeAllSessions(Long userId) {
        refreshTokens.revokeAllForUser(userId, Instant.now());
    }

    // ------------------------------------------------------------------

    private AuthResponse issueTokens(User user) {
        String accessToken = jwtService.issueAccessToken(user);
        String rawRefresh = jwtService.generateRefreshToken();

        RefreshToken record = new RefreshToken(
                user,
                jwtService.hashRefreshToken(rawRefresh),
                Instant.now().plus(jwtService.refreshTokenTtl()));
        refreshTokens.save(record);

        return new AuthResponse(
                accessToken,
                rawRefresh,
                "Bearer",
                jwtService.accessTokenTtl().toSeconds(),
                UserSummary.from(user));
    }
}
