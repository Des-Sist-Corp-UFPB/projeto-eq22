package com.iwrite.dashboard.dto;

import java.time.LocalDate;

public record WritingProgressPeriodResponse(
        String value,
        LocalDate startDate,
        LocalDate endDate
) {
}
