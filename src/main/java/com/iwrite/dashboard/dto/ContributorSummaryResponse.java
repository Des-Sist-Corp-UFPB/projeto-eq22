package com.iwrite.dashboard.dto;

import java.util.UUID;

public record ContributorSummaryResponse(
        UUID userId,
        String displayName
) {
}
