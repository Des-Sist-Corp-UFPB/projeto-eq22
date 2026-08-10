package com.iwrite.sceneversion.dto;

import com.iwrite.sceneversion.entity.SceneVersion;
import com.iwrite.sceneversion.entity.SceneVersionSource;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SceneVersionDetailResponse(
        UUID id,
        UUID sceneId,
        UUID originalSceneId,
        String sceneTitleSnapshot,
        String contentJson,
        String contentText,
        Integer wordCount,
        SceneVersionSource source,
        OffsetDateTime createdAt
) {

    public static SceneVersionDetailResponse fromEntity(SceneVersion version) {
        return new SceneVersionDetailResponse(
                version.getId(),
                version.getScene() == null ? null : version.getScene().getId(),
                version.getOriginalSceneId(),
                version.getSceneTitleSnapshot(),
                version.getContentJson(),
                version.getContentText(),
                version.getWordCount(),
                version.getSource(),
                version.getCreatedAt()
        );
    }
}
