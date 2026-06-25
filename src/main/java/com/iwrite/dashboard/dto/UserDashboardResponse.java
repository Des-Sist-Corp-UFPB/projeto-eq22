package com.iwrite.dashboard.dto;

import java.util.List;

public record UserDashboardResponse(
        WritingProgressPeriodResponse period,
        UserWritingSummaryResponse summary,
        List<UserDailyWritingResponse> dailySeries,
        List<UserBookContributionResponse> bookContributions
) {
}
