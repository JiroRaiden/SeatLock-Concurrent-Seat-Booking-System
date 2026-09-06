package com.seatlock.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatlock.exception.ErrorCode;
import com.seatlock.security.JwtAuthenticationFilter;
import com.seatlock.web.dto.ApiErrorResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The security filter chain: who can reach what, and what happens when they
 * cannot.
 */
@Configuration
@EnableMethodSecurity   // turns on @PreAuthorize / @PostAuthorize
public class SecurityConfig {

    private final SecurityProperties properties;
    private final ObjectMapper objectMapper;

    public SecurityConfig(SecurityProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * BCrypt with a cost factor of 12.
     *
     * <p>Cost is a power of two: 12 means 2^12 = 4096 key-derivation rounds,
     * roughly 250ms on a modern server. That number is chosen from both ends.
     * Too low and an attacker with a leaked table can try billions of candidate
     * passwords per second on a GPU; too high and your own login endpoint becomes
     * a denial-of-service amplifier, because each attempt costs you the same CPU
     * it costs them. 10-12 is the current sensible band, and it should be
     * re-examined every couple of years as hardware improves.
     *
     * <p>BCrypt salts each hash automatically and stores the salt in the output
     * string, which is why there is no separate salt column in the schema.
     *
     * <p>Argon2id is the stronger modern choice because it is memory-hard and so
     * resists GPU attack far better. BCrypt is used here because it needs no
     * native library and no tuning of three interacting parameters, and because
     * the {@code password_hash} column is sized to allow the swap later without
     * a migration.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtAuthenticationFilter jwtFilter,
                                           RateLimitFilter rateLimitFilter) throws Exception {

        http
            // ---- CSRF ----------------------------------------------------
            // Disabled, and this is one of the few places where that is correct.
            // CSRF works because a browser attaches cookies to cross-site
            // requests automatically. We authenticate with a bearer token that
            // the browser attaches to nothing on its own - a malicious page can
            // make the request, but cannot add our Authorization header. No
            // ambient credential means no CSRF.
            //
            // The moment this API moves to cookie authentication, CSRF
            // protection has to come straight back on.
            .csrf(csrf -> csrf.disable())

            .cors(cors -> cors.configurationSource(corsConfigurationSource()))

            // ---- No sessions --------------------------------------------
            // STATELESS means Spring never creates an HttpSession and never
            // consults one. Every request is authenticated purely from its own
            // token, so any instance can serve any request and scaling out needs
            // no sticky sessions and no shared session store.
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // ---- Response headers ---------------------------------------
            .headers(headers -> headers
                // Stop a browser from guessing a response is HTML when we said
                // it was JSON. Without it, a crafted upload echoed back can be
                // sniffed as a document and executed in our origin.
                .contentTypeOptions(Customizer.withDefaults())
                // We never render HTML, so nothing here should ever be framed.
                .frameOptions(f -> f.deny())
                // Do not leak our full URLs (which contain booking ids) in the
                // Referer header of outbound links.
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                // HSTS: once a browser has seen this, it refuses to talk to us
                // over plaintext HTTP for a year, which closes the downgrade
                // window on the very first request of a later visit.
                .httpStrictTransportSecurity(h -> h
                        .includeSubDomains(true)
                        .maxAgeInSeconds(31_536_000))
            )

            // ---- Who can reach what -------------------------------------
            // Rules are evaluated top to bottom, first match wins, so the order
            // here is part of the meaning. anyRequest().authenticated() is last
            // and is a deny-by-default backstop: a new endpoint added tomorrow
            // is protected unless somebody deliberately opens it, rather than
            // exposed unless somebody remembers to protect it.
            .authorizeHttpRequests(auth -> auth
                // CORS preflight carries no credentials and must never be
                // challenged, or every cross-origin call fails before it starts.
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                .requestMatchers("/api/v1/auth/register",
                                 "/api/v1/auth/login",
                                 "/api/v1/auth/refresh").permitAll()

                // Browsing is public; buying is not.
                .requestMatchers(HttpMethod.GET, "/api/v1/events/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/cities").permitAll()

                // Liveness and readiness must answer before anything is warm,
                // and a load balancer cannot present a token.
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()

                // Metrics carry operational detail (traffic shape, error rates)
                // and are for the scraper, not the public.
                .requestMatchers("/actuator/**").hasRole("ADMIN")

                // API docs: handy in dev, and a free map of the attack surface
                // in production. Left public here because this is a portfolio
                // project meant to be explored; the deployment guide notes that
                // a real deployment should restrict or disable it.
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()

                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")

                .anyRequest().authenticated()
            )

            // ---- Error responses ----------------------------------------
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) ->
                        writeError(response, ErrorCode.UNAUTHENTICATED))
                .accessDeniedHandler((request, response, deniedException) ->
                        writeError(response, ErrorCode.FORBIDDEN))
            )

            // ---- Filter order -------------------------------------------
            // Rate limiting runs FIRST, before any authentication work. The
            // point of a rate limit on /auth/login is to stop credential
            // stuffing, and BCrypt costs us ~250ms of CPU per attempt - so a
            // limiter placed after authentication would already have paid the
            // cost it exists to avoid.
            .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Writes our standard error envelope from inside the security filter chain.
     *
     * <p>Necessary because failures here happen <em>before</em> a controller is
     * chosen, so {@code @RestControllerAdvice} never sees them. Without this,
     * a 401 would come back as Spring's default HTML error page while every
     * other error was JSON - and the frontend's error parser would break on
     * exactly the response it most needs to understand.
     */
    private void writeError(jakarta.servlet.http.HttpServletResponse response, ErrorCode code)
            throws java.io.IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiErrorResponse body = new ApiErrorResponse(
                code.name(), code.defaultMessage(), code.status().value(),
                Instant.now(), null, Map.of(), Map.of());
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    /**
     * CORS, configured from an explicit allow-list.
     *
     * <p>The frontend is served from S3/CloudFront and the API from EC2, so they
     * are different origins and CORS is unavoidable.
     *
     * <p>Two things here are worth stating plainly:
     *
     * <ul>
     *   <li>{@code setAllowedOrigins} with exact strings, never
     *       {@code setAllowedOriginPatterns("*")}. A wildcard combined with
     *       credentials is the classic misconfiguration that lets any site on
     *       the internet read this API as the logged-in user.</li>
     *   <li>{@code setAllowCredentials(false)}. We authenticate with a bearer
     *       header, not a cookie, so the browser has no credential to attach and
     *       we do not need to ask for one. Turning it on "just in case" would
     *       weaken the wildcard rules the browser applies.</li>
     * </ul>
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.getCors().getAllowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        // Headers the browser is allowed to expose to our JavaScript.
        config.setExposedHeaders(List.of("Retry-After"));
        config.setAllowCredentials(false);
        // Cache the preflight for an hour so we are not answering OPTIONS before
        // every single call.
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
