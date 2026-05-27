package com.iwrite.dashboard.dto;

import com.iwrite.scene.entity.SceneStatus;

import java.util.List;

public record StatusCountResponse(
        SceneStatus status,
        int scenesCount,
        int wordCount,
        List<DashboardSceneSummaryResponse> scenes
) {
}
