package com.iwrite.llm.gateway;

import com.iwrite.llm.LlmTokenUsage;
import org.springframework.ai.chat.metadata.Usage;

/**
 * Maps Spring AI usage metadata to the persisted token abstraction. Spring AI
 * reports zeros when a provider omits usage; an all-zero report is treated as
 * absent so counts are never invented.
 */
public final class SpringAiUsageMapper {

    private SpringAiUsageMapper() {
    }

    public static LlmTokenUsage toTokenUsage(Usage usage) {
        if (usage == null) {
            return null;
        }
        Integer input = usage.getPromptTokens();
        Integer output = usage.getCompletionTokens();
        Integer total = usage.getTotalTokens();
        if (isAbsent(input) && isAbsent(output) && isAbsent(total)) {
            return null;
        }
        return new LlmTokenUsage(input, output, total);
    }

    private static boolean isAbsent(Integer tokens) {
        return tokens == null || tokens == 0;
    }
}
