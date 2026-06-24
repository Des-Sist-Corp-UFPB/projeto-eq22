package com.iwrite.tenant.repository;

import com.iwrite.tenant.entity.TenantMembership;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TenantMembershipRepository extends JpaRepository<TenantMembership, UUID> {

    boolean existsByTenant_IdAndUser_Id(UUID tenantId, UUID userId);
}
