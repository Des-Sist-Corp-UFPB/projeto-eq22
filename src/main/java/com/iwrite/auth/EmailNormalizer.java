package com.iwrite.auth;

import java.util.Locale;

/**
 * The one place email addresses are normalized before being used as a lookup key or stored — by
 * {@link com.iwrite.auth.AuthController#register}, {@link com.iwrite.auth.AuthController#login} and
 * {@link com.iwrite.auth.CredentialProvisioningRunner}. Trims surrounding whitespace and lowercases,
 * so the same address always resolves to the same {@code users.email} row regardless of how a
 * caller capitalized or padded it.
 *
 * <p><strong>ASCII-only policy</strong> (#149 review): {@code String.toLowerCase(Locale.ROOT)} and
 * PostgreSQL's {@code lower()} are not guaranteed to canonicalize every Unicode code point the same
 * way. For ASCII (U+0000-U+007F) they always agree, so this slice restricts account emails to ASCII
 * and rejects anything else via {@link #isAscii(String)} — full Unicode/EAI email support is out of
 * scope here. See {@code chk_users_email_ascii} (V33) for the database-level twin of this rule.
 *
 * <p><strong>Lowercase only after the ASCII check, never before</strong> (#149 review, round 9): a
 * handful of non-ASCII code points — e.g. U+212A KELVIN SIGN — lowercase to a plain ASCII letter
 * under {@code Locale.ROOT}. Lowercasing first and checking {@link #isAscii(String)} on the result,
 * as this method used to, would let such a code point sail through the check disguised as ASCII,
 * silently coercing a distinct address into colliding with (or masquerading as) an unrelated,
 * genuinely-ASCII one. {@link #normalize(String)} therefore only lowercases a value that is already
 * ASCII after trimming; every caller still finishes the check by calling {@link #isAscii(String)} on
 * the result, which now sees the untouched original whenever it was not ASCII to begin with.
 */
public final class EmailNormalizer {

    private EmailNormalizer() {
    }

    public static String normalize(String email) {
        if (email == null) {
            return null;
        }
        String trimmed = email.trim();
        return isAscii(trimmed) ? trimmed.toLowerCase(Locale.ROOT) : trimmed;
    }

    /** True only if every code point is ASCII (&lt;= U+007F). Intended to run on an already-{@link
     *  #normalize(String) normalized} value, ahead of any lookup, bcrypt call or write. */
    public static boolean isAscii(String value) {
        return value != null && value.codePoints().allMatch(codePoint -> codePoint <= 0x7F);
    }
}
