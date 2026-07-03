package com.iwrite.llm.gateway;

/**
 * Thrown when an execution fails because of invalid AI configuration.
 * Classified as {@code FAILED / CONFIGURATION_ERROR}.
 */
public class LlmConfigurationException extends RuntimeException {

    public LlmConfigurationException(String message) {
        super(message);
    }
}
