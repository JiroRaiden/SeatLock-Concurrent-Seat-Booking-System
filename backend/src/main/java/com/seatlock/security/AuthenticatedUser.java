package com.seatlock.security;

import com.seatlock.domain.Role;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.List;

/**
 * The authenticated caller, as reconstructed from their access token.
 *
 * <p>A record, and everything in it came out of a signed token - so it is exactly
 * what the token vouched for and nothing more. In particular it holds no
 * password hash, no entity, and no open Hibernate session.
 *
 * <p>Deliberately <b>not</b> the {@link com.seatlock.domain.User} entity.
 * Attaching a JPA entity to the security context is a familiar source of pain:
 * it keeps a detached object alive for the whole request, invites lazy-loading
 * exceptions from unexpected places, and quietly puts the password hash one
 * accidental {@code toString()} away from a log file.
 *
 * <p>Controllers receive this via {@code @AuthenticationPrincipal}.
 */
public record AuthenticatedUser(Long id, String email, Role role) {

    /**
     * What {@code hasRole(...)} and {@code @PreAuthorize} compare against.
     *
     * <p>Our enum constants are already named {@code ROLE_USER} / {@code ROLE_ADMIN},
     * so the string in the database, the string in the JWT and the string Spring
     * checks are literally the same value. Half the confusion around Spring
     * Security roles comes from that prefix being added in one place and not
     * another; here it is added nowhere, because it is already there.
     */
    public Collection<? extends GrantedAuthority> authorities() {
        return List.of(new SimpleGrantedAuthority(role.name()));
    }

    public boolean isAdmin() {
        return role == Role.ROLE_ADMIN;
    }
}
