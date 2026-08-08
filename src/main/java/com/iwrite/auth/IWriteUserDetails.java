package com.iwrite.auth;

import com.iwrite.tenant.entity.TenantMembershipRole;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Authenticated principal stored in the server-side session.
 *
 * <p>Holds the tenant resolved from a persisted membership at login time. The tenant is never
 * taken from the request, and {@link com.iwrite.auth.AuthSessionService#revalidate} re-reads the
 * membership from the database on every session read, so a revoked membership cannot survive in
 * a still-open session.
 */
public class IWriteUserDetails implements UserDetails, CredentialsContainer {

    private final UUID userId;
    private final UUID tenantId;
    private final String email;
    private final TenantMembershipRole role;

    /** Erased by {@code ProviderManager} right after authentication, so no hash reaches the session. */
    private String passwordHash;

    public IWriteUserDetails(UUID userId, UUID tenantId, String email, TenantMembershipRole role, String passwordHash) {
        this.userId = userId;
        this.tenantId = tenantId;
        this.email = email;
        this.role = role;
        this.passwordHash = passwordHash;
    }

    public UUID userId() {
        return userId;
    }

    public UUID tenantId() {
        return tenantId;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public void eraseCredentials() {
        passwordHash = null;
    }
}
