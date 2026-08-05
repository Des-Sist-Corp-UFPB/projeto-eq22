package com.iwrite.auth;

/**
 * Password policy enforced on registration. Documented here and in {@code README.md} /
 * {@code docs/authentication-multitenancy.md}: at least {@value #MIN_LENGTH} characters, with at
 * least one letter and one digit. The frontend mirrors this check for convenience only — this is
 * the authoritative check, since a client can always skip its own validation.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 10;

    private PasswordPolicy() {
    }

    public static boolean isValid(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
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
