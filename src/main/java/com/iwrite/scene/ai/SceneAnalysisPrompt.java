package com.iwrite.scene.ai;

public record SceneAnalysisPrompt(
        String sceneText,
        String focus,
        boolean truncated
) {
}
