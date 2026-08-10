package com.iwrite.dashboard.dto;

import java.time.LocalDate;

public record ContributionDailyWritingResponse(
        LocalDate date,
        long productiveWords,
        long manuscriptAdjustments
) {
}
