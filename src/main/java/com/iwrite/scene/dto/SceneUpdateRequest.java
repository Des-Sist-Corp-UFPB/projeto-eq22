package com.iwrite.scene.dto;

import com.iwrite.scene.entity.SceneStatus;
import jakarta.validation.constraints.PositiveOrZero;

public record SceneUpdateRequest(
        String title,
        String summary,
        SceneStatus status,
        @PositiveOrZero Integer sortOrder
) {
}
