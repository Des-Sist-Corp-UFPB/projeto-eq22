package com.iwrite.scene.dto;

import com.iwrite.scene.entity.SceneStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.UUID;

public record SceneRequest(
        @NotBlank String title,
        String summary,
        SceneStatus status,
        @PositiveOrZero Integer sortOrder,
        String contentJson,
        String contentText,
        UUID operationId
) {
    public SceneRequest(
            String title,
            String summary,
            SceneStatus status,
            Integer sortOrder,
            String contentJson,
            String contentText
    ) {
        this(title, summary, status, sortOrder, contentJson, contentText, null);
    }
}
