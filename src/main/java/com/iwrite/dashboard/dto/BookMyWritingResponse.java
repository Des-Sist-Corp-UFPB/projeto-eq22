package com.iwrite.dashboard.dto;

public record BookMyWritingResponse(
        WritingProgressDashboardResponse progress,
        WritingScheduleResponse schedule
) {
}
