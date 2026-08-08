package com.iwrite.auth;

/**
 * Either the calling origin or the targeted account has spent its login attempt budget for the
 * current window. Deliberately unchecked and message-free: {@link GlobalExceptionHandler} always
 * answers with the same generic {@link AuthMessages#TOO_MANY_LOGIN_ATTEMPTS}, so nothing about
 * which dimension tripped can leak through an exception message logged or serialized elsewhere.
 */
public class LoginRateLimitExceededException extends RuntimeException {
}
