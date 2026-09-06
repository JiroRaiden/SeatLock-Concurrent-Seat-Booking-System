package com.seatlock.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Reads {@code Authorization: Bearer &lt;jwt&gt;} and, if it verifies, populates
 * the security context for this request.
 *
 * <h2>What this filter deliberately does not do</h2>
 *
 * <p>It never rejects anything. A missing or invalid token simply leaves the
 * request anonymous, and the filter chain continues. Whether anonymous is
 * acceptable is a question about the endpoint, not about the token, and it is
 * answered later by the authorization rules in {@code SecurityConfig}.
 *
 * <p>That separation matters: if this filter returned 401 on a bad token, then a
 * request to a <em>public</em> endpoint carrying a stale token would fail, even
 * though it needed no authentication at all. Browsing the event list should not
 * break because a token expired in a background tab.
 *
 * <h2>Why {@link OncePerRequestFilter}</h2>
 *
 * A plain {@code Filter} can run several times for one request - forwards, error
 * dispatches, and async re-dispatches all re-enter the chain. Doing JWT parsing
 * three times per request is waste; worse, on an error dispatch the context may
 * already have been cleared for a reason, and re-populating it would resurrect
 * an identity the framework had just discarded.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {

        String token = extractBearerToken(request);

        // Only attempt authentication if nobody has already been authenticated.
        // Another filter earlier in the chain may legitimately have done so, and
        // silently overwriting an established identity would be a way to
        // downgrade a stronger authentication to a weaker one.
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {

            jwtService.parseAccessToken(token).ifPresent(principal -> {

                // Credentials are null: the token has already been verified and
                // there is nothing further to prove. Keeping the raw token here
                // would leave it reachable from anything that touches the
                // security context, including some debug logging paths.
                var authentication = new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        principal.authorities());

                authentication.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request));

                // Build a fresh context rather than mutating the existing one.
                // In Spring Security 6 the deferred/lazy context makes mutating
                // the shared instance unreliable, and this form is what the
                // framework documents.
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
            });
        }

        chain.doFilter(request, response);

        // No explicit cleanup here: Spring Security's own
        // SecurityContextHolderFilter clears the holder at the end of every
        // request. Clearing it ourselves mid-chain would break anything
        // downstream that still expects an authenticated caller.
    }

    /**
     * Pull the token out of the Authorization header.
     *
     * <p>Case-sensitive on "Bearer " because RFC 6750 defines the scheme that
     * way, and being lenient here achieves nothing except making it harder to
     * reason about what a valid request looks like.
     */
    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * Skip the filter entirely for endpoints that can never be authenticated.
     *
     * <p>Purely an optimisation - these paths are public anyway - but it keeps
     * the health check free of JWT parsing, which matters when a load balancer
     * probes it every few seconds.
     */
    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/health");
    }
}
