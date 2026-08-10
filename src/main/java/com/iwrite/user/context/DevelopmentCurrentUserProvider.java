package com.iwrite.user.context;

import java.time.ZoneId;
import java.util.UUID;

public class DevelopmentCurrentUserProvider implements CurrentUserProvider {

    private final UUID userId;
    private final UUID tenantId;
    private final ZoneId effectiveZoneId;

    public DevelopmentCurrentUserProvider(UUID userId, UUID tenantId, ZoneId effectiveZoneId) {
        this.userId = userId;
        this.tenantId = tenantId;
        this.effectiveZoneId = effectiveZoneId;
    }

    @Override
    public UUID userId() {
        return userId;
    }

    @Override
    public UUID tenantId() {
        return tenantId;
    }

    @Override
    public ZoneId effectiveZoneId() {
        return effectiveZoneId;
    }
}
