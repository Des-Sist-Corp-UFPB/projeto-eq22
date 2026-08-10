package com.iwrite.dashboard.dto;

import java.util.UUID;

public record EntityUsageResponse(
        UUID id,
        String name,
        int scenesCount
) {
}
