package com.iwrite.scene.dto;

import jakarta.validation.constraints.Size;

public record SceneAnalysisRequest(
        @Size(max = 300) String focus
) {
}
