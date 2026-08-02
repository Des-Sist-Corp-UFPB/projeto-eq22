package com.iwrite.tenant.repository;

import com.iwrite.tenant.entity.TenantMembership;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantMembershipRepository extends JpaRepository<TenantMembership, UUID> {

    boolean existsByTenant_IdAndUser_Id(UUID tenantId, UUID userId);

    List<TenantMembership> findByUser_Id(UUID userId);

    Optional<TenantMembership> findByTenant_IdAndUser_Id(UUID tenantId, UUID userId);
}
