package com.iwrite.support;

import com.iwrite.user.context.CurrentUserProvider;

import java.time.ZoneId;
import java.util.UUID;

public class SwitchableCurrentUserProvider implements CurrentUserProvider {

    public static final UUID DEFAULT_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    public static final UUID DEFAULT_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final ZoneId DEFAULT_ZONE_ID = ZoneId.of("America/Sao_Paulo");

    private Identity identity = defaultIdentity();

    public void switchTo(UUID userId, UUID tenantId, ZoneId effectiveZoneId) {
        identity = new Identity(userId, tenantId, effectiveZoneId);
    }

    public void reset() {
        identity = defaultIdentity();
    }

    @Override
    public UUID userId() {
        return identity.userId();
    }

    @Override
    public UUID tenantId() {
        return identity.tenantId();
    }

    @Override
    public ZoneId effectiveZoneId() {
        return identity.effectiveZoneId();
    }

    private Identity defaultIdentity() {
        return new Identity(DEFAULT_USER_ID, DEFAULT_TENANT_ID, DEFAULT_ZONE_ID);
    }

    private record Identity(UUID userId, UUID tenantId, ZoneId effectiveZoneId) {
    }
}
