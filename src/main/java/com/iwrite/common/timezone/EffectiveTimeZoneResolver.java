package com.iwrite.common.timezone;

import org.springframework.stereotype.Component;

import java.time.ZoneId;

@Component
public class EffectiveTimeZoneResolver {

    private final IanaZoneIdValidator validator;

    public EffectiveTimeZoneResolver(IanaZoneIdValidator validator) {
        this.validator = validator;
    }

    public ZoneId resolve(String userTimeZoneId, String tenantDefaultTimeZoneId) {
        if (userTimeZoneId != null) {
            return validator.validate(userTimeZoneId);
        }
        if (tenantDefaultTimeZoneId != null) {
            return validator.validate(tenantDefaultTimeZoneId);
        }
        return ZoneId.of("UTC");
    }
}
