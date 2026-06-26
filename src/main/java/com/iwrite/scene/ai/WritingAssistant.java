package com.iwrite.scene.ai;

import com.iwrite.scene.dto.SceneAnalysisResponse;

public interface WritingAssistant {

    SceneAnalysisResponse analyzeScene(SceneAnalysisPrompt prompt);
}
