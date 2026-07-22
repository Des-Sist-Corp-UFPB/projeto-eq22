package com.iwrite.scene.dto;

import java.util.List;

public record SceneAnalysisResponse(
        String summary,
        String tone,
        String pacing,
        List<String> strengths,
        List<String> issues,
        List<String> suggestions
) {
}
