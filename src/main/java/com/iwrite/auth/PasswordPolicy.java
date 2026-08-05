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
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            hasLetter = hasLetter || Character.isLetter(c);
            hasDigit = hasDigit || Character.isDigit(c);
        }
        return hasLetter && hasDigit;
    }
}
