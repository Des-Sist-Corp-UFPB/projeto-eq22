package com.iwrite.common.timezone;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EffectiveTimeZoneResolverTest {

    private final EffectiveTimeZoneResolver resolver =
            new EffectiveTimeZoneResolver(new IanaZoneIdValidator());

    @Test
    void userTimeZoneHasPriority() {
        assertEquals(
                ZoneId.of("Asia/Shanghai"),
                resolver.resolve("Asia/Shanghai", "Europe/Lisbon")
        );
    }

    @Test
    void tenantTimeZoneIsUsedWhenUserTimeZoneIsAbsent() {
        assertEquals(
                ZoneId.of("Europe/Lisbon"),
                resolver.resolve(null, "Europe/Lisbon")
        );
    }

    @Test
    void utcIsUsedOnlyWhenBothTimeZonesAreAbsent() {
        assertEquals(ZoneId.of("UTC"), resolver.resolve(null, null));
    }

    @Test
    void invalidPersistedUserTimeZoneFailsClearly() {
        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolve("UTC-03:00", "Europe/Lisbon")
        );
    }

    @Test
    void invalidPersistedTenantTimeZoneFailsClearly() {
        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolve(null, "Unknown/Nowhere")
        );
    }
}
