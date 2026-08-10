package com.iwrite.scene.ai;

import com.iwrite.llm.gateway.LlmCallResult;
import com.iwrite.scene.dto.SceneAnalysisResponse;

public interface WritingAssistant {

    String provider();

    String model();

    LlmCallResult<SceneAnalysisResponse> analyzeScene(SceneAnalysisPrompt prompt);
}
