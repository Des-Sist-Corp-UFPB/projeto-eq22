package com.iwrite.llm.cost;

import com.iwrite.llm.LlmTokenUsage;

import java.util.Optional;

/**
 * Optional, provider/model-aware cost estimation. Implementations must return
 * {@link Optional#empty()} when pricing is not configured or token usage is
 * incomplete; they must never fail the execution or report a misleading zero.
 */
public interface LlmCostEstimator {

    Optional<LlmCostEstimate> estimate(String provider, String model, LlmTokenUsage tokenUsage);
}
