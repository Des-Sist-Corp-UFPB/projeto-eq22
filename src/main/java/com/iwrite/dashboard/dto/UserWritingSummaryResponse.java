package com.iwrite.dashboard.dto;

public record UserWritingSummaryResponse(
        long productiveWords,
        long manuscriptAdjustments,
        long writingDays,
        long booksWrittenIn,
        long currentGlobalWritingStreak,
        long bestGlobalWritingStreak,
        long writingDaysThisMonth
) {
}
