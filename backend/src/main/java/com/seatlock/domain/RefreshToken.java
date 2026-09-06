package com.seatlock.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A server-side record of an issued refresh token.
 *
 * <h2>Why keep state when JWTs are meant to be stateless?</h2>
 *
 * Because "stateless" and "revocable" are opposites, and you need both - just
 * not in the same token. So we split the job:
 *
 * <ul>
 *   <li><b>Access token</b> - a stateless JWT, 15 minutes, never checked against
 *       the database. Fast: every API call validates it with one signature check
 *       and no I/O. If it is stolen, the damage window is 15 minutes.</li>
 *   <li><b>Refresh token</b> - opaque to the client, but recorded here. It lives
 *       7 days, so it <em>must</em> be revocable: logging out, changing your
 *       password, or an admin disabling an account has to actually end the
 *       session. That requires state.</li>
 * </ul>
 *
 * <h2>Why store a hash and not the token?</h2>
 *
 * A refresh token is a bearer credential: whoever holds it can mint access
 * tokens. Storing it in plaintext means a read-only SQL injection or a leaked
 * backup hands an attacker live sessions for every user. We store SHA-256 of the
 * token and compare hashes, so the table is useless on its own.
 *
 * <p>(Plain SHA-256 rather than BCrypt is right <em>here</em> and wrong for
 * passwords. Passwords are low-entropy and human-chosen, so they need a slow
 * hash to make guessing expensive. This token is 256 bits of output from a CSPRNG
 * - there is nothing to guess, so the only requirement is preimage resistance,
 * and a fast hash keeps token refresh cheap.)
 *
 * <h2>Rotation with reuse detection</h2>
 *
 * Each use of a refresh token revokes it and issues a new one, with
 * {@code replacedBy} pointing at the successor. If an already-rotated token is
 * presented again, that means two parties hold the same token - the legitimate
 * user and a thief. We cannot tell which is which, so we revoke the entire
 * chain and force a fresh login. This is the standard OAuth 2.0 BCP behaviour.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Hex-encoded SHA-256 of the token the client holds. Never the token. */
    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replaced_by")
    private RefreshToken replacedBy;

    protected RefreshToken() { }

    public RefreshToken(User user, String tokenHash, Instant expiresAt) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public boolean isUsable(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    public void revoke(Instant now) {
        if (this.revokedAt == null) {
            this.revokedAt = now;
        }
    }

    public void replaceWith(RefreshToken successor, Instant now) {
        revoke(now);
        this.replacedBy = successor;
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getTokenHash() { return tokenHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public RefreshToken getReplacedBy() { return replacedBy; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RefreshToken other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() { return RefreshToken.class.hashCode(); }
}
