package com.iwrite.dashboard.dto;

import java.time.LocalDate;

/**
 * Pre-V18 daily rows preserve their migrated productive values because restore/delete/import causes cannot be reconstructed.
 */
public record DailyWritingProgressResponse(
        LocalDate date,
        Integer dailyTargetWordCount,
        int startingManuscriptWordCount,
        int endingManuscriptWordCount,
        int productiveWordCountChange,
        int manuscriptAdjustmentWordCount,
        Double progressPercent
) {
}
