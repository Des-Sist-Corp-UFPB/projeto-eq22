package com.iwrite.dashboard.dto;

import java.time.LocalDate;

public record DailyWritingProgressResponse(
        LocalDate date,
        Integer dailyTargetWordCount,
        int startWordCount,
        int endWordCount,
        int netWordCountChange,
        Double progressPercent
) {
}
