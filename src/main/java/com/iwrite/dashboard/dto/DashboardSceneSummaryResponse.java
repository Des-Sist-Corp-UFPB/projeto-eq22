package com.iwrite.dashboard.dto;

import com.iwrite.scene.entity.SceneStatus;

import java.util.List;
import java.util.UUID;

public record DashboardSceneSummaryResponse(
        UUID sceneId,
        String title,
        String summary,
        SceneStatus status,
        int wordCount,
        UUID chapterId,
        String chapterTitle,
        UUID sectionId,
        String sectionTitle,
        String povCharacterName,
        String mainLocationName,
        List<String> participantNames,
        List<String> itemNames,
        String goal,
        String conflict,
        String outcome,
        String planningNotes
) {
}
