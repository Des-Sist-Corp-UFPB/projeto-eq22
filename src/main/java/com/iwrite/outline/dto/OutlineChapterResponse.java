package com.iwrite.outline.dto;

import java.util.List;
import java.util.UUID;

public record OutlineChapterResponse(
        UUID id,
        String title,
        String summary,
        Integer sortOrder,
        Integer wordCount,
        List<OutlineSceneResponse> scenes
) {
}
