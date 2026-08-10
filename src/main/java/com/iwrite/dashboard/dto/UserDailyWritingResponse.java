package com.iwrite.dashboard.dto;

import java.time.LocalDate;

public record UserDailyWritingResponse(
        LocalDate date,
        long productiveWords,
        long manuscriptAdjustments
) {
}
