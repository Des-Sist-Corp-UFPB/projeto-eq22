package com.iwrite.auth;

import java.util.Locale;

/**
 * The one place email addresses are normalized before being used as a lookup key or stored — by
 * both {@link com.iwrite.auth.AuthController#register} and {@link com.iwrite.auth.AuthController#login}.
 * Trims surrounding whitespace and lowercases, so the same address always resolves to the same
 * {@code users.email} row regardless of how a caller capitalized or padded it.
 *
 * <p><strong>ASCII-only policy</strong> (#149 review): {@code String.toLowerCase(Locale.ROOT)} and
 * PostgreSQL's {@code lower()} are not guaranteed to canonicalize every Unicode code point the same
 * way. For ASCII (U+0000-U+007F) they always agree, so this slice restricts account emails to ASCII
 * and rejects anything else via {@link #isAscii(String)} — full Unicode/EAI email support is out of
 * scope here. See {@code chk_users_email_ascii} (V33) for the database-level twin of this rule.
 */
public final class EmailNormalizer {

    private EmailNormalizer() {
    }

    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /** True only if every code point is ASCII (&lt;= U+007F). Intended to run on an already-{@link
     *  #normalize(String) normalized} value, ahead of any lookup, bcrypt call or write. */
    public static boolean isAscii(String value) {
        return value != null && value.codePoints().allMatch(codePoint -> codePoint <= 0x7F);
    }
}
