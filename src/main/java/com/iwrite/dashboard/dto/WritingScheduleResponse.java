package com.iwrite.dashboard.dto;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

public record WritingScheduleResponse(
        List<DayOfWeek> plannedWritingDays,
        int plannedWritingDaysPerWeek,
        List<DayOfWeek> restDays,
        boolean todayPlannedWritingDay,
        LocalDate currentScheduleEffectiveFrom
) {
}
