package com.iwrite.auth;

/**
 * The registration endpoint's own budget (never the login one) has been spent for the current
 * window. Deliberately unchecked and message-free, mirroring {@link LoginRateLimitExceededException}:
 * {@link com.iwrite.common.exception.GlobalExceptionHandler} always answers with
 * {@link RegistrationMessages#TOO_MANY_REGISTRATION_ATTEMPTS}.
 */
public class RegistrationRateLimitExceededException extends RuntimeException {
}
