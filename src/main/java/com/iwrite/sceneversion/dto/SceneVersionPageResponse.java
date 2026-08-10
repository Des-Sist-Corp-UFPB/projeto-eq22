package com.iwrite.sceneversion.dto;

import java.util.List;

public record SceneVersionPageResponse(
        List<SceneVersionSummaryResponse> items,
        int page,
        int size,
        boolean hasNext
) {
}
