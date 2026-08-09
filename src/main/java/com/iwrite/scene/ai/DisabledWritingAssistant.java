package com.iwrite.scene.ai;

import com.iwrite.llm.gateway.LlmCallResult;
import com.iwrite.llm.gateway.LlmFeatureDisabledException;
import com.iwrite.scene.dto.SceneAnalysisResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${spring.ai.model.chat:none}' != 'openai' && '${spring.ai.model.chat:none}' != 'anthropic'")
public class DisabledWritingAssistant implements WritingAssistant {

    @Override
    public String provider() {
        return "disabled";
    }

    @Override
    public String model() {
        return null;
    }

    @Override
    public LlmCallResult<SceneAnalysisResponse> analyzeScene(SceneAnalysisPrompt prompt) {
        throw new LlmFeatureDisabledException();
    }
}
