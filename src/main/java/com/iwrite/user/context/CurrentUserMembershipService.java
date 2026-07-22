package com.iwrite.user.context;

import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.tenant.repository.TenantMembershipRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class CurrentUserMembershipService {

    private final CurrentUserProvider currentUserProvider;
    private final TenantMembershipRepository membershipRepository;

    public CurrentUserMembershipService(
            CurrentUserProvider currentUserProvider,
            TenantMembershipRepository membershipRepository
    ) {
        this.currentUserProvider = currentUserProvider;
        this.membershipRepository = membershipRepository;
    }

    public UUID requireCurrentUserMemberId() {
        UUID userId = currentUserProvider.userId();
        UUID tenantId = currentUserProvider.tenantId();
        if (!membershipRepository.existsByTenant_IdAndUser_Id(tenantId, userId)) {
            throw new ResourceNotFoundException("Current user membership not found for tenant: " + tenantId);
        }
        return userId;
    }
}
