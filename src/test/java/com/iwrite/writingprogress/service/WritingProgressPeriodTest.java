package com.iwrite.writingprogress.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class WritingProgressPeriodTest {

    @ParameterizedTest
    @CsvSource({
            "THREE_MONTHS,2026-03-01",
            "SIX_MONTHS,2025-12-01",
            "TWELVE_MONTHS,2025-06-01"
    })
    void monthlyPeriodsStartAtFirstDayOfVisibleBucketRange(WritingProgressPeriod period, LocalDate expectedStartDate) {
        LocalDate today = LocalDate.of(2026, 5, 30);

        assertThat(period.startDateInclusive(today)).isEqualTo(expectedStartDate);
    }
}
