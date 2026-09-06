package com.seatlock.repository;

import com.seatlock.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Case-insensitive lookup, matching the {@code UNIQUE INDEX ON LOWER(email)}
     * in the schema.
     *
     * <p>The comparison must be done the same way in both places. If Java looked
     * up by exact match while the database enforced uniqueness on the lowercase
     * form, then registering {@code Bishal@x.com} when {@code bishal@x.com}
     * exists would fail with a constraint violation the code never expected -
     * and, worse, a login attempt for one casing could miss an account that
     * exists under another.
     */
    @Query("SELECT u FROM User u WHERE LOWER(u.email) = LOWER(:email)")
    Optional<User> findByEmailIgnoreCase(@Param("email") String email);

    @Query("SELECT COUNT(u) > 0 FROM User u WHERE LOWER(u.email) = LOWER(:email)")
    boolean existsByEmailIgnoreCase(@Param("email") String email);
}
