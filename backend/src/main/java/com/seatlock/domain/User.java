package com.seatlock.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * An account.
 *
 * <p>Note what this class does <em>not</em> contain: a plaintext password field.
 * The raw password exists only as a local variable inside the registration and
 * login flows, and is hashed before it ever touches an object that could be
 * serialised, logged, or persisted. If there is no field, it cannot leak.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Stored as the user typed it (so we can greet them with the right casing),
     * but the database enforces uniqueness on {@code LOWER(email)} and every
     * lookup normalises to lowercase first. See {@link #normaliseEmail}.
     */
    @Column(nullable = false, length = 320)
    private String email;

    /**
     * A BCrypt hash. {@code @JsonIgnore} is not needed here because this entity
     * is never returned from a controller - every response goes through an
     * explicit DTO. That is a deliberate rule: entity classes are for the
     * database, records in web/dto are for the wire. Serialising entities is how
     * password hashes end up in API responses.
     */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    /**
     * Stored as the string 'ROLE_USER' / 'ROLE_ADMIN'.
     *
     * <p>{@code EnumType.STRING}, never {@code EnumType.ORDINAL}. Ordinal stores
     * the enum's position as an integer, so reordering the enum constants
     * silently rewrites the meaning of every existing row - the classic way a
     * refactor turns every user into an admin.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role = Role.ROLE_USER;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected User() {
        // JPA requires a no-arg constructor. Kept protected so application code
        // is pushed towards the meaningful constructor below.
    }

    public User(String email, String passwordHash, String displayName, Role role) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.role = role;
    }

    /** The canonical form used for lookups and uniqueness. */
    public static String normaliseEmail(String raw) {
        return raw == null ? null : raw.trim().toLowerCase(java.util.Locale.ROOT);
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public Role getRole() { return role; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getCreatedAt() { return createdAt; }

    /**
     * Entity equality by primary key only, and only when the key is set.
     * Using all fields would break the moment Hibernate mutates a field inside a
     * HashSet; using {@code getClass()} would break with lazy proxies.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof User other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        // Constant hash is correct (if unexciting) for entities whose id is
        // assigned by the database after insertion.
        return User.class.hashCode();
    }

    @Override
    public String toString() {
        // Never include email or hash: toString() output ends up in logs.
        return "User{id=" + id + ", role=" + role + "}";
    }
}
