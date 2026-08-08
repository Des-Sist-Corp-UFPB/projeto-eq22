package com.iwrite.llm.gateway;

import com.iwrite.llm.audit.LlmErrorCategory;
import com.iwrite.llm.audit.LlmExecutionStatus;

public record LlmErrorClassification(LlmExecutionStatus status, LlmErrorCategory category) {
}
