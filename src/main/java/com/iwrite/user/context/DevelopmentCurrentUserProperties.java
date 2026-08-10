package com.iwrite.user.context;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.UUID;

@ConfigurationProperties(prefix = "iwrite.current-user.development")
public class DevelopmentCurrentUserProperties {

    private boolean enabled = false;
    private UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private String timeZoneId = "America/Sao_Paulo";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public String getTimeZoneId() {
        return timeZoneId;
    }

    public void setTimeZoneId(String timeZoneId) {
        this.timeZoneId = timeZoneId;
    }
}
