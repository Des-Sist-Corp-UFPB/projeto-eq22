package com.iwrite.dashboard.dto;

import java.util.List;

public record WritingProgressDashboardResponse(
        DailyWritingProgressResponse today,
        List<DailyWritingProgressResponse> recentDays
) {
}
