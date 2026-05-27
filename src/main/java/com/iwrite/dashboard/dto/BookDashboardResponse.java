package com.iwrite.dashboard.dto;

import java.util.List;
import java.util.UUID;

public record BookDashboardResponse(
        UUID bookId,
        String title,
        int totalWordCount,
        Integer targetWordCount,
        Integer remainingWordCount,
        Double wordCountProgressPercent,
        Integer exceededTargetWordCount,
        int totalSections,
        int totalChapters,
        int totalScenes,
        WritingProgressDashboardResponse writingProgress,
        PlanningProgressResponse planningProgress,
        List<StatusCountResponse> scenesByStatus,
        List<PovStatsResponse> povStats,
        NarrativeGapsResponse narrativeGaps,
        List<EntityUsageResponse> mostUsedCharacters,
        List<EntityUsageResponse> mostUsedLocations,
        List<EntityUsageResponse> mostUsedItems
) {
}
