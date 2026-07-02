package com.iwrite.llm.gateway;

import java.util.UUID;

/**
 * Per-execution context handed to the provider call. The trace ID correlates
 * the product request, the provider call, sanitized logs, and the persisted
 * audit row.
 */
public record LlmCallContext(UUID traceId) {
}
