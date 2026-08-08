package com.iwrite.llm.audit;

import com.iwrite.audit.entity.AuditResourceType;
import com.iwrite.llm.LlmFeature;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row per logical LLM execution. Rows are created in {@code STARTED} state
 * before the provider call and finished through the conditional terminal update
 * in {@link LlmExecutionAuditRepository}, never through entity mutation.
 *
 * <p>Only bounded operational metadata is stored. Manuscript content, prompts,
 * model responses, credentials, and exception messages must never reach this
 * entity.
 */
@Entity
@Table(name = "llm_execution_audits")
public class LlmExecutionAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 64)
    private LlmFeature feature;

    @Column(nullable = false, updatable = false, length = 64)
    private String provider;

    @Column(length = 128)
    private String model;

    @Column(nullable = false, updatable = false, length = 64)
    private String promptVersion;

    @Column(nullable = false, updatable = false)
    private UUID traceId;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false, length = 64)
    private AuditResourceType resourceType;

    @Column(updatable = false)
    private UUID resourceId;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime startedAt;

    private OffsetDateTime completedAt;

    private Long latencyMs;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private LlmExecutionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 64)
    private LlmErrorCategory errorCategory;

    private Integer inputTokens;

    private Integer outputTokens;

    private Integer totalTokens;

    @Column(precision = 12, scale = 6)
    private BigDecimal estimatedCost;

    @Column(length = 8)
    private String costCurrency;

    @Column(nullable = false)
    private boolean fallbackUsed;

    protected LlmExecutionAudit() {
    }

    public LlmExecutionAudit(
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
        this.tenantId = tenantId;
        this.userId = userId;
        this.feature = feature;
        this.provider = provider;
        this.model = model;
        this.promptVersion = promptVersion;
        this.traceId = traceId;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.startedAt = startedAt;
        this.status = LlmExecutionStatus.STARTED;
        this.fallbackUsed = false;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getUserId() {
        return userId;
    }

    public LlmFeature getFeature() {
        return feature;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public UUID getTraceId() {
        return traceId;
    }

    public AuditResourceType getResourceType() {
        return resourceType;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public LlmExecutionStatus getStatus() {
        return status;
    }

    public LlmErrorCategory getErrorCategory() {
        return errorCategory;
    }

    public Integer getInputTokens() {
        return inputTokens;
    }

    public Integer getOutputTokens() {
        return outputTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public BigDecimal getEstimatedCost() {
        return estimatedCost;
    }

    public String getCostCurrency() {
        return costCurrency;
    }

    public boolean isFallbackUsed() {
        return fallbackUsed;
    }
}
