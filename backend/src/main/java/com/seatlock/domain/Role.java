package com.seatlock.domain;

/**
 * Authorities, stored and compared as strings.
 *
 * <p>The {@code ROLE_} prefix is not decoration. Spring Security's
 * {@code hasRole("ADMIN")} silently prepends {@code ROLE_} before comparing,
 * while {@code hasAuthority("ADMIN")} does not. Naming the constants with the
 * prefix already applied means the value in the database, the value in the JWT,
 * and the value Spring compares against are all literally the same string, and
 * there is no place for the mismatch to hide.
 */
public enum Role {
    ROLE_USER,
    ROLE_ADMIN;

    /** The bare name Spring's {@code hasRole()} expects. */
    public String shortName() {
        return name().substring("ROLE_".length());
    }
}
