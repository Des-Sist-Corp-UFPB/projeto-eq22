package com.iwrite.scene.ai;

import com.iwrite.common.exception.ServiceUnavailableException;
import com.iwrite.scene.dto.SceneAnalysisResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${spring.ai.model.chat:none}' != 'openai'")
public class DisabledWritingAssistant implements WritingAssistant {

    @Override
    public SceneAnalysisResponse analyzeScene(SceneAnalysisPrompt prompt) {
        throw new ServiceUnavailableException(
                "AI scene analysis is not available. Set spring.ai.model.chat=openai to enable it."
        );
    }
}
