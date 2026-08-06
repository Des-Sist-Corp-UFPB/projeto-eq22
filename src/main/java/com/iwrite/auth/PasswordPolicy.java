package com.iwrite.auth;

/**
 * Password policy enforced on registration. Documented here and in {@code README.md} /
 * {@code docs/authentication-multitenancy.md}: at least {@value #MIN_LENGTH} characters, with at
 * least one letter and one digit, and at most {@value #MAX_UTF8_BYTES} UTF-8 bytes. The frontend
 * mirrors this check for convenience only — this is the authoritative check, since a client can
 * always skip its own validation.
 *
 * <p>{@value #MAX_UTF8_BYTES} matches bcrypt's effective input limit (the
 * {@link org.springframework.security.crypto.password.DelegatingPasswordEncoder}-selected encoder):
 * bcrypt silently ignores any byte past the 72nd, so without this cap two different passwords that
 * share the same 72-byte UTF-8 prefix would hash identically. Rejecting outright, rather than
 * truncating, means no such collision can ever be registered. The byte length itself is measured by
 * {@link BcryptInputPolicy#encode(String)}, not {@code String.getBytes(UTF_8)} — the latter
 * silently substitutes an unpaired surrogate instead of rejecting it, which would let two distinct
 * malformed passwords encode to the same bytes (#149 review).
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_UTF8_BYTES = 72;

    private PasswordPolicy() {
    }

    public static boolean isValid(String password) {
        if (password == null || password.codePointCount(0, password.length()) < MIN_LENGTH) {
            return false;
        }
        if (!BcryptInputPolicy.isValid(password, MAX_UTF8_BYTES)) {
            return false;
        }
        // codePoints(), not charAt()/Character.isLetter(char): a supplementary-plane letter or digit
        // (outside the BMP) is a surrogate pair, and the char overloads only ever see one half of
        // that pair — neither half is itself a letter or digit, so the char-based scan silently
        // never counts it. The frontend mirrors this with \p{L}/\p{Nd}, which already match by code
        // point, so this keeps the two in agreement rather than the backend under-accepting what the
        // frontend already allowed through.
        boolean hasLetter = password.codePoints().anyMatch(Character::isLetter);
        boolean hasDigit = password.codePoints().anyMatch(Character::isDigit);
        return hasLetter && hasDigit;
    }
}
