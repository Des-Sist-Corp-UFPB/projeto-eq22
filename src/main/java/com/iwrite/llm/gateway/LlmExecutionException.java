package com.iwrite.llm.gateway;

import com.iwrite.llm.audit.LlmErrorCategory;
import com.iwrite.llm.audit.LlmExecutionStatus;

import java.util.UUID;

/**
 * Safe execution failure surfaced to calling features. The message is a fixed
 * text derived from the stable error category and never contains provider
 * error details, prompt content, or credentials.
 */
public class LlmExecutionException extends RuntimeException {

    private final LlmExecutionStatus status;
    private final LlmErrorCategory errorCategory;
    private final UUID traceId;

    public LlmExecutionException(
            LlmExecutionStatus status,
            LlmErrorCategory errorCategory,
            UUID traceId,
            Throwable cause
    ) {
        super(safeMessage(errorCategory), cause);
        this.status = status;
        this.errorCategory = errorCategory;
        this.traceId = traceId;
    }

    public LlmExecutionStatus getStatus() {
        return status;
    }

    public LlmErrorCategory getErrorCategory() {
        return errorCategory;
    }

    public UUID getTraceId() {
        return traceId;
    }

    private static String safeMessage(LlmErrorCategory errorCategory) {
        return switch (errorCategory) {
            case PROVIDER_TIMEOUT -> "The AI provider did not answer in time.";
            case PROVIDER_UNAVAILABLE -> "The AI provider is temporarily unavailable.";
            case PROVIDER_REQUEST_REJECTED -> "The AI provider rejected the request.";
            case INVALID_STRUCTURED_RESPONSE -> "The AI provider returned an invalid response.";
            case CONFIGURATION_ERROR -> "The AI integration is misconfigured.";
            case FEATURE_DISABLED -> "This AI feature is disabled.";
            case AUDIT_PERSISTENCE_FAILURE -> "The AI execution could not be audited and was aborted.";
            case INTERNAL_EXECUTION_ERROR -> "The AI execution failed.";
        };
    }
}
