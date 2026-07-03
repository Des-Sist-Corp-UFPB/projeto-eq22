package com.iwrite.llm.audit;

/**
 * Stable, operational error categories. Raw exception messages are never
 * persisted; only one of these categories is.
 */
public enum LlmErrorCategory {
    PROVIDER_TIMEOUT,
    PROVIDER_UNAVAILABLE,
    PROVIDER_REQUEST_REJECTED,
    INVALID_STRUCTURED_RESPONSE,
    CONFIGURATION_ERROR,
    FEATURE_DISABLED,
    AUDIT_PERSISTENCE_FAILURE,
    INTERNAL_EXECUTION_ERROR
}
