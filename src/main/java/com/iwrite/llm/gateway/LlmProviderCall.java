package com.iwrite.llm.gateway;

/**
 * Provider invocation supplied by the calling feature. Implementations own the
 * prompt and the typed conversion; the gateway only ever sees the returned
 * metadata, never prompt or response content.
 */
@FunctionalInterface
public interface LlmProviderCall<T> {

    LlmCallResult<T> call(LlmCallContext context) throws Exception;
}
