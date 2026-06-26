package com.iwrite.scene.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "iwrite.ai.openai.chat.options")
public class OpenAiChatGenerationProperties {

    private String temperature;
    private String maxTokens;
    private String maxCompletionTokens;
    private String reasoningEffort;

    public String getTemperature() {
        return temperature;
    }

    public void setTemperature(String temperature) {
        this.temperature = temperature;
    }

    public String getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(String maxTokens) {
        this.maxTokens = maxTokens;
    }

    public String getMaxCompletionTokens() {
        return maxCompletionTokens;
    }

    public void setMaxCompletionTokens(String maxCompletionTokens) {
        this.maxCompletionTokens = maxCompletionTokens;
    }

    public String getReasoningEffort() {
        return reasoningEffort;
    }

    public void setReasoningEffort(String reasoningEffort) {
        this.reasoningEffort = reasoningEffort;
    }
}
