package com.iwrite.scene.dto;

import com.iwrite.scene.entity.SceneStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

public record SceneRequest(
        @NotBlank String title,
        String summary,
        SceneStatus status,
        @PositiveOrZero Integer sortOrder,
        String contentJson,
        String contentText
) {
}
