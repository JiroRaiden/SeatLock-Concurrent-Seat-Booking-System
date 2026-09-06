package com.seatlock.repository;

import com.seatlock.domain.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

    @Query("""
           SELECT k FROM IdempotencyKey k
            WHERE k.user.id = :userId
              AND k.idemKey = :key
           """)
    Optional<IdempotencyKey> findByUserAndKey(@Param("userId") Long userId,
                                              @Param("key") String key);

    /**
     * Keys are only meaningful while a client might still be retrying. Keeping
     * them forever turns a hot table into a slow one, so a scheduled job drops
     * anything older than the retention window.
     */
    @Modifying
    @Query("DELETE FROM IdempotencyKey k WHERE k.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
