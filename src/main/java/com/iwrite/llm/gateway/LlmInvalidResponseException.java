package com.iwrite.llm.gateway;

/**
 * Thrown by a provider call when the model output cannot be converted into the
 * expected structured value. Must not carry response content in its message.
 */
public class LlmInvalidResponseException extends RuntimeException {

    public LlmInvalidResponseException(Throwable cause) {
        super("The provider response could not be converted into the expected structure.", cause);
    }
}
