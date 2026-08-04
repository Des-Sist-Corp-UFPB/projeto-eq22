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
    INTERNAL_EXECUTION_ERROR;

    /**
     * True when the category means this service or its configuration is broken,
     * as opposed to an expected runtime condition (provider timeout, disabled
     * feature, malformed model output).
     *
     * <p>Single source of truth for log severity across every caller: an
     * expected failure must never share a level with a genuine defect, or
     * alerting on {@code ERROR} would never observe the second. It lives on the
     * category itself because the MCP layer needs the same answer <em>before</em>
     * {@code McpToolException.from} collapses every {@code LlmExecutionException}
     * into the client-facing {@code unavailable}.
     */
    public boolean isInternalFailure() {
        return this == INTERNAL_EXECUTION_ERROR
                || this == AUDIT_PERSISTENCE_FAILURE
                || this == CONFIGURATION_ERROR;
    }
}
