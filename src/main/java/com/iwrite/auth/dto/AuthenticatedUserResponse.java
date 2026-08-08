package com.iwrite.auth.dto;

/**
 * Session payload for {@code /api/auth/login} and {@code /api/auth/me}.
 *
 * <p>Carries no identifiers: the browser never learns a userId or tenantId, and therefore can
 * never send one back as a claim of identity.
 */
public record AuthenticatedUserResponse(User user, ActiveWorkspace activeWorkspace) {

    public record User(String displayName, String email) {
    }

    public record ActiveWorkspace(String name, String role) {
    }
}
