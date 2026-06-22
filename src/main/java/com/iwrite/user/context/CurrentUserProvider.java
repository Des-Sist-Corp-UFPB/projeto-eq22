package com.iwrite.user.context;

import java.time.ZoneId;
import java.util.UUID;

public interface CurrentUserProvider {

    UUID userId();

    UUID tenantId();

    ZoneId effectiveZoneId();
}
