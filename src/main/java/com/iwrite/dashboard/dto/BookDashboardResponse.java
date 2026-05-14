package com.iwrite.dashboard.dto;

import java.util.List;
import java.util.UUID;

public record BookDashboardResponse(
        UUID bookId,
        String title,
        int totalWordCount,
        int totalSections,
        int totalChapters,
        int totalScenes,
        PlanningProgressResponse planningProgress,
        List<StatusCountResponse> scenesByStatus,
        List<PovStatsResponse> povStats,
        NarrativeGapsResponse narrativeGaps,
        List<EntityUsageResponse> mostUsedCharacters,
        List<EntityUsageResponse> mostUsedLocations,
        List<EntityUsageResponse> mostUsedItems
) {
}
