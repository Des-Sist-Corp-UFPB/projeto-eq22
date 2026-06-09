package com.iwrite.sceneversion.dto;

import com.iwrite.sceneversion.entity.SceneVersion;
import com.iwrite.sceneversion.entity.SceneVersionSource;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SceneVersionSummaryResponse(
        UUID id,
        UUID sceneId,
        UUID originalSceneId,
        String sceneTitleSnapshot,
        Integer wordCount,
        SceneVersionSource source,
        OffsetDateTime createdAt,
        String contentTextPreview
) {

    private static final int PREVIEW_LENGTH = 180;

    public static SceneVersionSummaryResponse fromEntity(SceneVersion version) {
        return new SceneVersionSummaryResponse(
                version.getId(),
                version.getScene() == null ? null : version.getScene().getId(),
                version.getOriginalSceneId(),
                version.getSceneTitleSnapshot(),
                version.getWordCount(),
                version.getSource(),
                version.getCreatedAt(),
                preview(version.getContentText())
        );
    }

    private static String preview(String contentText) {
        if (contentText == null || contentText.isBlank()) {
            return "";
        }

        String normalized = contentText.trim().replaceAll("\\s+", " ");
        return normalized.length() <= PREVIEW_LENGTH ? normalized : normalized.substring(0, PREVIEW_LENGTH) + "...";
    }
}
