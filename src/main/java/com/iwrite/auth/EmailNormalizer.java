package com.iwrite.auth;

import java.util.Locale;

/**
 * The one place email addresses are normalized before being used as a lookup key or stored — by
 * both {@link com.iwrite.auth.AuthController#register} and {@link com.iwrite.auth.AuthController#login}.
 * Trims surrounding whitespace and lowercases, so the same address always resolves to the same
 * {@code users.email} row regardless of how a caller capitalized or padded it.
 */
public final class EmailNormalizer {

    private EmailNormalizer() {
    }

    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
