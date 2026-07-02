/**
 * Provider-neutral LLM execution gateway and specialized LLM execution auditing.
 *
 * <p>Every product LLM call must run through
 * {@link com.iwrite.llm.gateway.LlmExecutionGateway}, which centralizes provider
 * invocation, timing, error classification, token usage collection, optional cost
 * estimation, and audit persistence. Only bounded operational metadata is ever
 * persisted; manuscript content, prompts, model responses, and credentials are not.
 *
 * <p>See {@code docs/llm-execution-audit.md} for the data policy, retention notes,
 * and current limitations.
 */
package com.iwrite.llm;
