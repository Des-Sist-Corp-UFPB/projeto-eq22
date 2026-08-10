package com.iwrite.llm.audit;

import com.iwrite.llm.LlmTokenUsage;
import com.iwrite.llm.cost.LlmCostEstimate;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Terminal metadata for one execution. Successful completions carry no error
 * category; failed completions carry a stable category and no usage or cost.
 */
public record LlmExecutionCompletion(
        LlmExecutionStatus status,
        String model,
        OffsetDateTime completedAt,
        long latencyMs,
        LlmErrorCategory errorCategory,
        LlmTokenUsage tokenUsage,
        LlmCostEstimate estimatedCost,
        boolean fallbackUsed
) {

    public LlmExecutionCompletion {
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(completedAt, "completedAt is required");
        if (!status.isTerminal()) {
            throw new IllegalArgumentException("Completion status must be terminal.");
        }
        if (status == LlmExecutionStatus.SUCCEEDED && errorCategory != null) {
            throw new IllegalArgumentException("Successful completions must not carry an error category.");
        }
        if (status != LlmExecutionStatus.SUCCEEDED && errorCategory == null) {
            throw new IllegalArgumentException("Failed completions require a stable error category.");
        }
        if (latencyMs < 0) {
            throw new IllegalArgumentException("latencyMs must not be negative.");
        }
    }

    public static LlmExecutionCompletion success(
            String model,
            OffsetDateTime completedAt,
            long latencyMs,
            LlmTokenUsage tokenUsage,
            LlmCostEstimate estimatedCost,
            boolean fallbackUsed
    ) {
        return new LlmExecutionCompletion(
                LlmExecutionStatus.SUCCEEDED,
                model,
                completedAt,
                latencyMs,
                null,
                tokenUsage,
                estimatedCost,
                fallbackUsed
        );
    }

    public static LlmExecutionCompletion failure(
            LlmExecutionStatus status,
            LlmErrorCategory errorCategory,
            String model,
            OffsetDateTime completedAt,
            long latencyMs
    ) {
        return new LlmExecutionCompletion(status, model, completedAt, latencyMs, errorCategory, null, null, false);
    }
}
