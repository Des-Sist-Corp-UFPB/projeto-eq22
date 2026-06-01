package com.iwrite.dashboard.dto;

public record WritingConsistencyResponse(
        int currentStreakDays,
        int bestStreakDays,
        int writingDaysThisMonth,
        int recentWindowDays,
        int recentWritingDays,
        double recentWritingDaysPercent,
        int recentPlannedWritingDays,
        int recentSuccessfulPlannedWritingDays,
        double recentPlannedWritingDaysPercent
) {
}
