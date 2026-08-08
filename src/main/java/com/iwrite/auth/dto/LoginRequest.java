package com.iwrite.auth.dto;

/**
 * Bean validation is deliberately omitted: a blank or missing field must fail with the same
 * generic authentication error as a wrong password, never with a field-level message.
 */
public record LoginRequest(String email, String password) {
}
