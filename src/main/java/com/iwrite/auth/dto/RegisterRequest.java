package com.iwrite.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Structural validation only (blank fields). Email format is checked in {@code RegistrationService}
 * *after* normalization — not here with {@code @Email} — because Bean Validation would run against
 * the raw, un-trimmed value and reject an address a user's browser padded with whitespace before
 * {@link EmailNormalizer} ever gets to trim it. Every other domain rule — password policy,
 * confirmation match, persona membership, IANA time zone — lives in the service too, matching the
 * project's convention of keeping domain validation in the service layer.
 *
 * <p>{@code passwordConfirmation} exists only to be checked against {@code password}; it is never
 * read again after that and never reaches persistence.
 */
public record RegisterRequest(
        @NotBlank String displayName,
        @NotBlank String email,
        @NotBlank String password,
        @NotBlank String passwordConfirmation,
        @NotBlank String primaryPersona,
        @NotBlank String timeZone
) {
}
