package com.seatlock.repository;

import com.seatlock.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    @Query("SELECT rt FROM RefreshToken rt JOIN FETCH rt.user WHERE rt.tokenHash = :hash")
    Optional<RefreshToken> findByTokenHash(@Param("hash") String hash);

    /**
     * Revoke every live token for a user in one statement.
     *
     * <p>Called on password change and on refresh-token reuse detection. Doing it
     * as a bulk UPDATE rather than load-loop-save matters here: if a token has
     * genuinely been stolen, the time between detecting it and killing the
     * session should be one round trip, not one per token.
     *
     * <p>{@code @Modifying(clearAutomatically = true)} is required because a bulk
     * JPQL update goes straight to the database and bypasses the persistence
     * context. Without clearing, an entity already loaded in this transaction
     * would still report {@code revokedAt == null} - stale, and in a security
     * check that is the difference between revoked and not.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           UPDATE RefreshToken rt
              SET rt.revokedAt = :now
            WHERE rt.user.id = :userId
              AND rt.revokedAt IS NULL
           """)
    int revokeAllForUser(@Param("userId") Long userId, @Param("now") Instant now);

    /** Housekeeping: tokens that expired long ago are dead weight. */
    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE rt.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
