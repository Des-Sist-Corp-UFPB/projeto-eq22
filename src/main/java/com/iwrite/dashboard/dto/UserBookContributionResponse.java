package com.iwrite.dashboard.dto;

import java.util.UUID;

public record UserBookContributionResponse(
        UUID bookId,
        String title,
        long productiveWords,
        long manuscriptAdjustments,
        long writingDays
) {
}
