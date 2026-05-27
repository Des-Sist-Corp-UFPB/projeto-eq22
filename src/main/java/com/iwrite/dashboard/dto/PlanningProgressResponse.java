package com.iwrite.dashboard.dto;

public record PlanningProgressResponse(
        int plannedScenesCount,
        int totalScenes,
        double plannedScenesPercent
) {
}
