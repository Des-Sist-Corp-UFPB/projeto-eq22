package com.iwrite.auth;

import com.iwrite.auth.dto.AuthenticatedUserResponse;
import com.iwrite.tenant.entity.TenantMembership;
import com.iwrite.tenant.repository.TenantMembershipRepository;
import com.iwrite.user.entity.User;
import com.iwrite.user.entity.UserCredential;
import com.iwrite.user.repository.UserCredentialRepository;
import com.iwrite.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Loads authentication state from the database.
 *
 * <p>Flow: {@code email -> user -> credential -> single active membership -> tenant}. Every
 * failure along the way raises {@link UsernameNotFoundException}, which
 * {@code DaoAuthenticationProvider} converts into the same {@code BadCredentialsException} as a
 * wrong password (and still runs its dummy hash, so the response time does not leak whether the
 * account exists).
 */
@Service
public class AuthSessionService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(AuthSessionService.class);

    private final UserRepository userRepository;
    private final UserCredentialRepository credentialRepository;
    private final TenantMembershipRepository membershipRepository;

    public AuthSessionService(
            UserRepository userRepository,
            UserCredentialRepository credentialRepository,
            TenantMembershipRepository membershipRepository
    ) {
        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.membershipRepository = membershipRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("No user matches the supplied email"));
        UserCredential credential = credentialRepository.findById(user.getId())
                .orElseThrow(() -> new UsernameNotFoundException("User has no stored credential"));
        TenantMembership membership = requireSingleMembership(user.getId());

        return new IWriteUserDetails(
                user.getId(),
                membership.getTenant().getId(),
                user.getEmail(),
                membership.getRole(),
                credential.getPasswordHash()
        );
    }

    /**
     * Re-reads the membership behind an open session. Returns empty when the user, the tenant or
     * the membership no longer exists, so a session can never keep authorising a revoked
     * relationship. Deleting a user or a tenant cascades to {@code tenant_memberships}, which is
     * why a single lookup covers all three cases.
     */
    @Transactional(readOnly = true)
    public Optional<AuthenticatedUserResponse> revalidate(IWriteUserDetails principal) {
        return membershipRepository
                .findByTenant_IdAndUser_Id(principal.tenantId(), principal.userId())
                .map(membership -> new AuthenticatedUserResponse(
                        new AuthenticatedUserResponse.User(
                                membership.getUser().getDisplayName(),
                                membership.getUser().getEmail()
                        ),
                        new AuthenticatedUserResponse.ActiveWorkspace(
                                membership.getTenant().getName(),
                                membership.getRole().name()
                        )
                ));
    }

    /**
     * The academic slice supports exactly one active membership per user. Both zero and more than
     * one are refused, because silently picking a workspace would make the tenant choice implicit.
     */
    private TenantMembership requireSingleMembership(UUID userId) {
        List<TenantMembership> memberships = membershipRepository.findByUser_Id(userId);
        if (memberships.size() != 1) {
            // Count only: never log the email, the hash or any credential material.
            log.debug("Login refused: user has {} active memberships, exactly 1 is required", memberships.size());
            throw new UsernameNotFoundException("User does not have exactly one active membership");
        }
        return memberships.get(0);
    }
}
