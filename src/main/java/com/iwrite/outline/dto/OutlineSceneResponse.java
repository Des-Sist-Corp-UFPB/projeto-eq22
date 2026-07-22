package com.iwrite.outline.dto;

import com.iwrite.scene.entity.SceneStatus;

import java.util.List;
import java.util.UUID;

public record OutlineSceneResponse(
        UUID id,
        String title,
        SceneStatus status,
        Integer sortOrder,
        Integer wordCount,
        UUID povCharacterId,
        String povCharacterName,
        List<String> planningGaps
) {
}
