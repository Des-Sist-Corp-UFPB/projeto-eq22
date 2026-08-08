package com.iwrite.scene.ai;

import com.iwrite.llm.gateway.LlmFeatureDisabledException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DisabledWritingAssistantTest {

    private final DisabledWritingAssistant assistant = new DisabledWritingAssistant();

    @Test
    void reportsDisabledProviderWithoutInvokingAnHttpLayerException() {
        assertThat(assistant.provider()).isEqualTo("disabled");
        assertThat(assistant.model()).isNull();
        assertThatThrownBy(() -> assistant.analyzeScene(new SceneAnalysisPrompt("scene", null, false)))
                .isInstanceOf(LlmFeatureDisabledException.class);
    }
}
