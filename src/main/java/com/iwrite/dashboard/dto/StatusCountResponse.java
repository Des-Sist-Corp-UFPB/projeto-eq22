package com.iwrite.dashboard.dto;

import com.iwrite.scene.entity.SceneStatus;

public record StatusCountResponse(
        SceneStatus status,
        int scenesCount,
        int wordCount
) {
}
