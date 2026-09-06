package com.seatlock.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Per-IP request budgets on the endpoints worth abusing.
 *
 * <h2>Why a token bucket rather than a counter</h2>
 *
 * A fixed-window counter ("100 requests per minute") has a boundary problem: a
 * client can send 100 at 11:59:59 and another 100 at 12:00:00 and pass every
 * check while actually sending 200 requests in one second.
 *
 * <p>A token bucket refills continuously. Ten tokens per minute means one token
 * every six seconds, so a burst of ten is allowed - real users do click twice -
 * but the sustained rate is genuinely capped, with no window to game.
 *
 * <h2>The honest limitation</h2>
 *
 * These buckets live in this JVM's heap. Run three instances behind a load
 * balancer and the effective limit is three times what is configured, because
 * each instance counts independently.
 *
 * <p>That is a deliberate, documented trade-off rather than an oversight. The
 * fix is Bucket4j's Redis backend, which moves the bucket into the Redis this
 * project already runs: the same {@code Bandwidth} definitions, a
 * {@code LettuceBasedProxyManager} instead of an in-memory map. The reason not
 * to do it here is that it puts a Redis round trip in front of every single
 * request, including the ones that will pass - and at this project's scale the
 * in-JVM version is the right call. Knowing which limitation you have chosen is
 * the point.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    /**
     * Bounded, self-evicting bucket store.
     *
     * <p>The bound is a security control, not a tuning knob. The map key is
     * derived from the client's IP address, which an attacker partly controls;
     * an unbounded {@code ConcurrentHashMap} would grow one entry per distinct
     * source address until the heap ran out. That turns a rate limiter - a piece
     * of anti-abuse machinery - into an amplifier for the abuse it prevents.
     */
    private static final int MAX_TRACKED_CLIENTS = 100_000;

    private final SecurityProperties properties;
    private final ObjectMapper objectMapper;

    private final Cache<String, Bucket> authBuckets;
    private final Cache<String, Bucket> holdBuckets;

    public RateLimitFilter(SecurityProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;

        this.authBuckets = Caffeine.newBuilder()
                .maximumSize(MAX_TRACKED_CLIENTS)
                // A bucket is only interesting while its owner is active. Ten
                // idle minutes means a full refill has happened anyway, so the
                // entry carries no information worth keeping.
                .expireAfterAccess(10, TimeUnit.MINUTES)
                .build();

        this.holdBuckets = Caffeine.newBuilder()
                .maximumSize(MAX_TRACKED_CLIENTS)
                .expireAfterAccess(10, TimeUnit.MINUTES)
                .build();
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {

        Cache<String, Bucket> bucketStore = storeFor(request);
        if (bucketStore == null) {
            chain.doFilter(request, response);   // endpoint is not rate limited
            return;
        }

        String clientKey = clientKey(request);
        int capacity = bucketStore == authBuckets
                ? properties.getRateLimit().getAuthRequestsPerMinute()
                : properties.getRateLimit().getHoldRequestsPerMinute();

        Bucket bucket = bucketStore.get(clientKey, key -> newBucket(capacity));

        // tryConsumeAndReturnRemaining, not tryConsume: we want the refill time
        // so the response can carry an accurate Retry-After. Telling a client
        // exactly when to come back is what stops a well-behaved one from
        // retrying in a tight loop and making the situation worse.
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            response.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
            chain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000L);
        writeTooManyRequests(response, retryAfterSeconds);
    }

    private Bucket newBucket(int perMinute) {
        // Bandwidth.simple(capacity, period) refills the whole capacity smoothly
        // over the period rather than dumping it all in at the boundary.
        return Bucket.builder()
                .addLimit(Bandwidth.simple(perMinute, Duration.ofMinutes(1)))
                .build();
    }

    private Cache<String, Bucket> storeFor(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();

        if (path.startsWith("/api/v1/auth/login")
                || path.startsWith("/api/v1/auth/register")
                || path.startsWith("/api/v1/auth/refresh")) {
            return authBuckets;
        }
        // POST /api/v1/events/{id}/holds
        if ("POST".equals(method) && path.startsWith("/api/v1/events/") && path.endsWith("/holds")) {
            return holdBuckets;
        }
        return null;
    }

    /**
     * The identity we count against.
     *
     * <p>{@code getRemoteAddr()} is used rather than reading
     * {@code X-Forwarded-For} directly, and the difference matters. That header
     * is client-supplied: anyone can send
     * {@code X-Forwarded-For: 1.2.3.4} and get a fresh budget on every request,
     * which defeats the limiter entirely and, worse, lets them frame an innocent
     * address.
     *
     * <p>We set {@code server.forward-headers-strategy=framework} in
     * application.yml, which installs Spring's {@code ForwardedHeaderFilter}.
     * That filter parses the header <em>and</em> is meant to sit behind a proxy
     * that overwrites it. So the rule is: trust {@code getRemoteAddr()}, and make
     * sure the load balancer in front of us strips and rewrites the header
     * rather than appending to whatever the client sent. The deployment notes
     * spell this out - a rate limiter is only as trustworthy as the proxy
     * configuration underneath it.
     */
    private String clientKey(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        return ip == null ? "unknown" : ip;
    }

    private void writeTooManyRequests(HttpServletResponse response, long retryAfterSeconds)
            throws IOException {
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        objectMapper.writeValue(response.getOutputStream(),
                com.seatlock.web.GlobalExceptionHandler.rateLimited(retryAfterSeconds));
    }
}
