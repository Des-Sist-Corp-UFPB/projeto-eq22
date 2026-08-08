package com.iwrite.user.context;

import com.iwrite.auth.IWriteUserDetails;
import com.iwrite.common.timezone.EffectiveTimeZoneResolver;
import com.iwrite.tenant.entity.TenantMembership;
import com.iwrite.tenant.repository.TenantMembershipRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationException;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.time.ZoneId;
import java.util.UUID;

/**
 * Identity for authenticated deployments: {@code session -> principal -> membership -> tenant}.
 *
 * <p>Nothing the browser sends takes part in this. The user comes from the {@link Authentication}
 * held in the session, and the tenant comes from the membership row that backs it — never from a
 * body, a query parameter or a header, which is why a request carrying somebody else's
 * {@code tenantId} still resolves to the caller's own tenant.
 *
 * <p>The principal carries the ids captured at login, but those are a lookup key, not a standing
 * authorization: every request re-reads the membership, so revoking it (or deleting the user or the
 * tenant, both of which cascade into {@code tenant_memberships}) stops the next request instead of
 * waiting for the session to expire.
 *
 * <p>Request-scoped so that one read serves the whole request and the following request reads
 * again. This is also mutually exclusive with {@link DevelopmentCurrentUserConfiguration}: that one
 * registers its provider only when the development identity is explicitly enabled, this one only
 * when it is not. Exactly one {@link CurrentUserProvider} exists in any given profile, so neither
 * {@code @Primary} nor bean ordering is involved in picking it.
 */
@Component
@RequestScope
@ConditionalOnProperty(
        prefix = "iwrite.current-user.development",
        name = "enabled",
        havingValue = "false",
        matchIfMissing = true
)
public class AuthenticatedCurrentUserProvider implements CurrentUserProvider {

    private final TenantMembershipRepository membershipRepository;
    private final EffectiveTimeZoneResolver effectiveTimeZoneResolver;

    private TenantMembership membership;

    public AuthenticatedCurrentUserProvider(
            TenantMembershipRepository membershipRepository,
            EffectiveTimeZoneResolver effectiveTimeZoneResolver
    ) {
        this.membershipRepository = membershipRepository;
        this.effectiveTimeZoneResolver = effectiveTimeZoneResolver;
    }

    @Override
    public UUID userId() {
        return requireMembership().getUser().getId();
    }

    @Override
    public UUID tenantId() {
        return requireMembership().getTenant().getId();
    }

    @Override
    public ZoneId effectiveZoneId() {
        TenantMembership current = requireMembership();
        return effectiveTimeZoneResolver.resolve(
                current.getUser().getTimeZoneId(),
                current.getTenant().getDefaultTimeZoneId()
        );
    }

    /**
     * Fails as an authentication problem rather than a domain one: a session whose membership no
     * longer resolves must produce {@code 401} and stop before any tenant-scoped query runs, not a
     * {@code 404} that would suggest the data merely went missing.
     */
    private TenantMembership requireMembership() {
        if (membership == null) {
            IWriteUserDetails principal = authenticatedPrincipal();
            membership = membershipRepository
                    .findWithUserAndTenantByTenant_IdAndUser_Id(principal.tenantId(), principal.userId())
                    .orElseThrow(() -> new SessionAuthenticationException(
                            "Session is no longer backed by a valid tenant membership"));
        }
        return membership;
    }

    private IWriteUserDetails authenticatedPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof IWriteUserDetails principal)) {
            // The filter chain already refuses anonymous calls; this covers anything that reaches a
            // tenant-scoped service without one, so the fallback is a refusal and never a default
            // identity.
            throw new SessionAuthenticationException("No authenticated IWrite principal is available");
        }
        return principal;
    }
}
