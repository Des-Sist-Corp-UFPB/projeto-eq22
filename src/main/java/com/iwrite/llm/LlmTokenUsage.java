package com.iwrite.llm;

/**
 * Token usage reported by a provider. Fields are null when the provider does not
 * report them; counts are never invented or approximated.
 */
public record LlmTokenUsage(Integer inputTokens, Integer outputTokens, Integer totalTokens) {

    public LlmTokenUsage {
        requireNotNegative(inputTokens, "inputTokens");
        requireNotNegative(outputTokens, "outputTokens");
        requireNotNegative(totalTokens, "totalTokens");
    }

    private static void requireNotNegative(Integer value, String field) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(field + " must not be negative.");
        }
    }
}
