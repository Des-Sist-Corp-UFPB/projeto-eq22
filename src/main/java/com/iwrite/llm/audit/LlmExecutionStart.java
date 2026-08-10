package com.iwrite.llm.audit;

import com.iwrite.audit.entity.AuditResourceType;
import com.iwrite.llm.LlmFeature;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Metadata persisted before the provider call. Contains identifiers only;
 * there is deliberately no field able to carry prompt or manuscript content.
 */
public record LlmExecutionStart(
        UUID tenantId,
        UUID userId,
        LlmFeature feature,
        String provider,
        String model,
        String promptVersion,
        UUID traceId,
        AuditResourceType resourceType,
        UUID resourceId,
        OffsetDateTime startedAt
) {

    public LlmExecutionStart {
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(userId, "userId is required");
        Objects.requireNonNull(feature, "feature is required");
        Objects.requireNonNull(provider, "provider is required");
        Objects.requireNonNull(promptVersion, "promptVersion is required");
        Objects.requireNonNull(traceId, "traceId is required");
        Objects.requireNonNull(startedAt, "startedAt is required");
    }
}
