package com.iwrite.llm.gateway;

import com.iwrite.llm.LlmTokenUsage;

/**
 * Result abstraction pairing the typed value with the provider metadata that a
 * plain typed conversion would discard. {@code tokenUsage} and {@code model}
 * stay null when the provider does not report them.
 */
public record LlmCallResult<T>(T value, LlmTokenUsage tokenUsage, String model, boolean fallbackUsed) {

    public static <T> LlmCallResult<T> of(T value) {
        return new LlmCallResult<>(value, null, null, false);
    }

    public LlmCallResult<T> withTokenUsage(LlmTokenUsage tokenUsage) {
        return new LlmCallResult<>(value, tokenUsage, model, fallbackUsed);
    }

    public LlmCallResult<T> withModel(String model) {
        return new LlmCallResult<>(value, tokenUsage, model, fallbackUsed);
    }

    public LlmCallResult<T> withFallbackUsed() {
        return new LlmCallResult<>(value, tokenUsage, model, true);
    }
}
