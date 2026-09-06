package com.seatlock.web.dto;

import com.seatlock.domain.User;

/**
 * The public view of an account.
 *
 * <p>Built by an explicit {@link #from} mapper rather than by serialising the
 * entity. That single decision is what guarantees {@code passwordHash} can never
 * appear in a response: it is not that we remembered to exclude it, it is that
 * there is nowhere for it to go.
 */
public record UserSummary(Long id, String email, String displayName, String role) {

    public static UserSummary from(User user) {
        return new UserSummary(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole().name());
    }
}
