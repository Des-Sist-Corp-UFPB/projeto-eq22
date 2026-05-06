package com.iwrite.scene.dto;

import com.iwrite.scene.entity.Scene;
import com.iwrite.scene.entity.SceneStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SceneResponse(
        UUID id,
        UUID bookId,
        UUID chapterId,
        String title,
        String summary,
        String contentJson,
        String contentText,
        SceneStatus status,
        Integer sortOrder,
        Integer wordCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static SceneResponse fromEntity(Scene scene) {
        return new SceneResponse(
                scene.getId(),
                scene.getBook().getId(),
                scene.getChapter().getId(),
                scene.getTitle(),
                scene.getSummary(),
                scene.getContentJson(),
                scene.getContentText(),
                scene.getStatus(),
                scene.getSortOrder(),
                scene.getWordCount(),
                scene.getCreatedAt(),
                scene.getUpdatedAt()
        );
    }
}
