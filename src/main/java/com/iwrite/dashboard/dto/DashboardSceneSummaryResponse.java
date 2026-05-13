package com.iwrite.dashboard.dto;

import com.iwrite.scene.entity.SceneStatus;

import java.util.UUID;

public record DashboardSceneSummaryResponse(
        UUID sceneId,
        String title,
        SceneStatus status,
        int wordCount,
        UUID chapterId,
        String chapterTitle,
        UUID sectionId,
        String sectionTitle
) {
}
