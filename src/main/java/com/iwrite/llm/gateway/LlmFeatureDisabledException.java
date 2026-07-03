package com.iwrite.llm.gateway;

/**
 * Thrown by a provider call when the feature or provider is disabled by
 * configuration. Classified as {@code DISABLED / FEATURE_DISABLED}.
 */
public class LlmFeatureDisabledException extends RuntimeException {

    public LlmFeatureDisabledException() {
        super("The AI feature is disabled by configuration.");
    }
}
