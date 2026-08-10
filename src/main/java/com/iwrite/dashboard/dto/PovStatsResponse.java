package com.iwrite.dashboard.dto;

import java.util.UUID;

public record PovStatsResponse(
        UUID characterId,
        String name,
        int scenesCount,
        int wordCount
) {
}
