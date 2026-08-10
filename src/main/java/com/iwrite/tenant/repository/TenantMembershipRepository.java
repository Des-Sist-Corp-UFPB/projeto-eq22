package com.iwrite.tenant.repository;

import com.iwrite.tenant.entity.TenantMembership;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantMembershipRepository extends JpaRepository<TenantMembership, UUID> {

    boolean existsByTenant_IdAndUser_Id(UUID tenantId, UUID userId);

    List<TenantMembership> findByUser_Id(UUID userId);

    Optional<TenantMembership> findByTenant_IdAndUser_Id(UUID tenantId, UUID userId);

    /**
     * Same lookup, but with the user and the tenant already loaded. The request-scoped
     * {@link com.iwrite.user.context.AuthenticatedCurrentUserProvider} keeps the result for the
     * rest of the request, past the transaction that read it, so nothing on it may still be lazy.
     */
    @EntityGraph(attributePaths = {"user", "tenant"})
    Optional<TenantMembership> findWithUserAndTenantByTenant_IdAndUser_Id(UUID tenantId, UUID userId);
}
