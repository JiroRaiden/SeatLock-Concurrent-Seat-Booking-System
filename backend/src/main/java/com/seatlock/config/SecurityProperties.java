package com.seatlock.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * Bound from {@code seatlock.security.*}.
 *
 * <p>Everything here is validated at startup. A misconfigured security setting
 * that only reveals itself under attack is worse than a service that refuses to
 * boot, so all the constraints are deliberately strict.
 */
@Validated
@ConfigurationProperties(prefix = "seatlock.security")
public class SecurityProperties {

    @Valid @NotNull
    private Jwt jwt = new Jwt();

    @Valid @NotNull
    private Cors cors = new Cors();

    @Valid @NotNull
    private RateLimit rateLimit = new RateLimit();

    public Jwt getJwt() { return jwt; }
    public void setJwt(Jwt jwt) { this.jwt = jwt; }
    public Cors getCors() { return cors; }
    public void setCors(Cors cors) { this.cors = cors; }
    public RateLimit getRateLimit() { return rateLimit; }
    public void setRateLimit(RateLimit rateLimit) { this.rateLimit = rateLimit; }

    public static class Jwt {

        /**
         * Base64-encoded signing key for HS256.
         *
         * <p>Must decode to at least 32 bytes. That is not a stylistic
         * preference - RFC 7518 requires an HMAC key at least as long as the
         * hash output, and JJWT will refuse to sign with anything shorter. A
         * short key makes the signature brute-forceable, which means anyone can
         * mint a token claiming {@code role: ROLE_ADMIN}.
         *
         * <p>The check itself lives in {@code JwtService}'s constructor, because
         * it has to happen after Base64 decoding.
         */
        @NotEmpty
        private String secret;

        /**
         * Written into the {@code iss} claim and verified on every token.
         *
         * <p>Verifying the issuer matters when a signing key is ever shared
         * between services: without it, a token minted by a sibling service for
         * its own purposes would be accepted here as a valid login.
         */
        @NotEmpty
        private String issuer = "seatlock";

        /**
         * Access token lifetime. Short on purpose: an access token cannot be
         * revoked, so its lifetime IS the compromise window. Fifteen minutes is
         * the usual balance between that window and how often clients have to
         * refresh.
         */
        @NotNull
        private Duration accessTokenTtl = Duration.ofMinutes(15);

        /** Refresh token lifetime. Long, but revocable - it is stored server-side. */
        @NotNull
        private Duration refreshTokenTtl = Duration.ofDays(7);

        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public String getIssuer() { return issuer; }
        public void setIssuer(String issuer) { this.issuer = issuer; }
        public Duration getAccessTokenTtl() { return accessTokenTtl; }
        public void setAccessTokenTtl(Duration accessTokenTtl) { this.accessTokenTtl = accessTokenTtl; }
        public Duration getRefreshTokenTtl() { return refreshTokenTtl; }
        public void setRefreshTokenTtl(Duration refreshTokenTtl) { this.refreshTokenTtl = refreshTokenTtl; }
    }

    public static class Cors {

        /**
         * Exact origins allowed to call this API from a browser.
         *
         * <p>{@code @NotEmpty} means the app will not start without an explicit
         * list. There is no wildcard option anywhere in this codebase, because
         * {@code allowedOrigins("*")} together with credentials is precisely how
         * a hostile page reads an authenticated API on the user's behalf. If you
         * genuinely need a public, credential-free API, that is a different
         * configuration and it should be written down as such.
         */
        @NotEmpty
        private List<String> allowedOrigins = List.of();

        public List<String> getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }
    }

    public static class RateLimit {

        /** Per-IP budget for login/register/refresh. Tight: these are the
         *  endpoints worth brute-forcing. */
        @Min(1)
        private int authRequestsPerMinute = 10;

        /** Per-IP budget for creating holds. Generous enough for a real user
         *  changing their mind, tight enough to stop a script from sweeping a
         *  screen. */
        @Min(1)
        private int holdRequestsPerMinute = 30;

        public int getAuthRequestsPerMinute() { return authRequestsPerMinute; }
        public void setAuthRequestsPerMinute(int v) { this.authRequestsPerMinute = v; }
        public int getHoldRequestsPerMinute() { return holdRequestsPerMinute; }
        public void setHoldRequestsPerMinute(int v) { this.holdRequestsPerMinute = v; }
    }
}
