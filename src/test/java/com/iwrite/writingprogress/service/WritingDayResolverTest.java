package com.iwrite.writingprogress.service;

import com.iwrite.user.context.CurrentUserProvider;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WritingDayResolverTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final MutableCurrentUserProvider currentUserProvider = new MutableCurrentUserProvider();
    private final WritingDayResolver resolver = new WritingDayResolver(
            Clock.fixed(Instant.parse("2026-06-02T23:30:00Z"), ZoneOffset.UTC),
            currentUserProvider
    );

    @Test
    void utcUserUsesUtcDate() {
        currentUserProvider.switchTo(ZoneOffset.UTC);

        assertThat(resolver.writingDateFor(Instant.parse("2026-06-02T23:30:00Z")))
                .isEqualTo(LocalDate.of(2026, 6, 2));
    }

    @Test
    void recifeUserUsesRecifeDate() {
        currentUserProvider.switchTo(ZoneId.of("America/Recife"));

        assertThat(resolver.writingDateFor(Instant.parse("2026-06-02T01:30:00Z")))
                .isEqualTo(LocalDate.of(2026, 6, 1));
    }

    @Test
    void positiveOffsetUserCanMoveToNextDate() {
        currentUserProvider.switchTo(ZoneId.of("Asia/Tokyo"));

        assertThat(resolver.writingDateFor(Instant.parse("2026-06-02T23:30:00Z")))
                .isEqualTo(LocalDate.of(2026, 6, 3));
    }

    @Test
    void negativeOffsetUserCanRemainOnPreviousDate() {
        currentUserProvider.switchTo(ZoneId.of("America/Los_Angeles"));

        assertThat(resolver.writingDateFor(Instant.parse("2026-06-02T06:30:00Z")))
                .isEqualTo(LocalDate.of(2026, 6, 1));
    }

    @Test
    void sameInstantCanProduceDifferentDatesForDifferentUserZones() {
        Instant instant = Instant.parse("2026-06-02T23:30:00Z");

        currentUserProvider.switchTo(ZoneId.of("America/New_York"));
        LocalDate newYorkDate = resolver.writingDateFor(instant);

        currentUserProvider.switchTo(ZoneId.of("Asia/Tokyo"));
        LocalDate tokyoDate = resolver.writingDateFor(instant);

        assertThat(newYorkDate).isEqualTo(LocalDate.of(2026, 6, 2));
        assertThat(tokyoDate).isEqualTo(LocalDate.of(2026, 6, 3));
    }

    @Test
    void currentWritingDateUsesClockInstantNearUtcMidnight() {
        currentUserProvider.switchTo(ZoneId.of("Asia/Tokyo"));

        assertThat(resolver.currentWritingDate()).isEqualTo(LocalDate.of(2026, 6, 3));
    }

    @Test
    void springForwardInstantMapsToValidNewYorkDate() {
        currentUserProvider.switchTo(ZoneId.of("America/New_York"));

        assertThat(resolver.writingDateFor(Instant.parse("2026-03-08T07:30:00Z")))
                .isEqualTo(LocalDate.of(2026, 3, 8));
    }

    @Test
    void bothFallBackRepeatedHourOccurrencesMapToSameNewYorkDate() {
        currentUserProvider.switchTo(ZoneId.of("America/New_York"));

        assertThat(resolver.writingDateFor(Instant.parse("2026-11-01T05:30:00Z")))
                .isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(resolver.writingDateFor(Instant.parse("2026-11-01T06:30:00Z")))
                .isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test
    void nullInstantIsRejectedClearly() {
        assertThatThrownBy(() -> resolver.writingDateFor(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("instant");
    }

    private static class MutableCurrentUserProvider implements CurrentUserProvider {

        private ZoneId zoneId = ZoneOffset.UTC;

        void switchTo(ZoneId zoneId) {
            this.zoneId = zoneId;
        }

        @Override
        public UUID userId() {
            return USER_ID;
        }

        @Override
        public UUID tenantId() {
            return TENANT_ID;
        }

        @Override
        public ZoneId effectiveZoneId() {
            return zoneId;
        }
    }
}
