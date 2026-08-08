package com.iwrite.llm.audit;

public enum LlmExecutionStatus {
    STARTED,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    UNAVAILABLE,
    INVALID_RESPONSE,
    DISABLED;

    public boolean isTerminal() {
        return this != STARTED;
    }
}
