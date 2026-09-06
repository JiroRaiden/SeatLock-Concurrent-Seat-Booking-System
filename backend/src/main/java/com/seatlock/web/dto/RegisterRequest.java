package com.seatlock.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Registration payload.
 *
 * <p><b>There is no {@code role} field, and that is the point.</b> If this record
 * had one, Jackson would bind it and a client could register as an administrator
 * by adding one line to the JSON. This is "mass assignment", and the fix is not
 * to filter the field later - it is to make the field impossible to send. The
 * role is decided by the server in {@code AuthService.register}.
 *
 * <p>The same reasoning is why request DTOs are separate types from entities
 * throughout this codebase. Binding straight onto a JPA entity means every
 * column is a potential input, including {@code enabled} and {@code version}.
 */
public record RegisterRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "Must be a valid email address")
        @Size(max = 320, message = "Email is too long")
        String email,

        /*
         * Length is the requirement, not a character-class puzzle.
         *
         * Rules like "one uppercase, one digit, one symbol" push people towards
         * Password1! - short, predictable, and in every cracking dictionary -
         * while a 14-character passphrase they can actually remember is far
         * stronger. NIST SP 800-63B moved away from composition rules for
         * exactly this reason.
         *
         * The 128 cap is not a security rule either; it stops someone posting a
         * megabyte of text and making the server spend real CPU BCrypting it.
         * (BCrypt only reads the first 72 bytes anyway - a genuine trap if you
         * ever assume a longer password is proportionally stronger.)
         */
        @NotBlank(message = "Password is required")
        @Size(min = 10, max = 128, message = "Password must be between 10 and 128 characters")
        String password,

        @NotBlank(message = "Name is required")
        @Size(min = 1, max = 100, message = "Name is too long")
        String displayName
) { }
