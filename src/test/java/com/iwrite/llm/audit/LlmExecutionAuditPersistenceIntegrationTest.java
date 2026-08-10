package com.iwrite.llm.audit;

import com.iwrite.audit.entity.AuditResourceType;
import com.iwrite.llm.LlmFeature;
import com.iwrite.llm.LlmTokenUsage;
import com.iwrite.llm.cost.LlmCostEstimate;
import com.iwrite.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LlmExecutionAuditPersistenceIntegrationTest extends PostgresIntegrationTest {

    private static final OffsetDateTime STARTED_AT =
            OffsetDateTime.of(2026, 7, 2, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime COMPLETED_AT = STARTED_AT.plusSeconds(3);

    @Autowired
    private LlmExecutionAuditRecorder recorder;

    @Autowired
    private LlmExecutionAuditRepository repository;

    @Test
    void persistsStartedExecutionWithFullIdentifyingMetadata() {
        UUID traceId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        UUID auditId = recorder.recordStart(start(UUID.randomUUID(), traceId, resourceId));

        LlmExecutionAudit audit = repository.findById(auditId).orElseThrow();
        assertThat(audit.getStatus()).isEqualTo(LlmExecutionStatus.STARTED);
        assertThat(audit.getFeature()).isEqualTo(LlmFeature.SCENE_ANALYSIS);
        assertThat(audit.getProvider()).isEqualTo("fake");
        assertThat(audit.getModel()).isEqualTo("fake-model");
        assertThat(audit.getPromptVersion()).isEqualTo("scene-analysis:v1");
        assertThat(audit.getTraceId()).isEqualTo(traceId);
        assertThat(audit.getResourceType()).isEqualTo(AuditResourceType.SCENE);
        assertThat(audit.getResourceId()).isEqualTo(resourceId);
        assertThat(audit.getStartedAt()).isEqualTo(STARTED_AT);
        assertThat(audit.getCompletedAt()).isNull();
        assertThat(audit.getLatencyMs()).isNull();
        assertThat(audit.getErrorCategory()).isNull();
        assertThat(audit.isFallbackUsed()).isFalse();
        assertThat(repository.findByTraceId(traceId)).isPresent();
    }

    @Test
    void persistsSuccessfulCompletionWithTokenUsageAndConfiguredCost() {
        UUID auditId = recorder.recordStart(start(UUID.randomUUID(), UUID.randomUUID(), null));

        boolean applied = recorder.complete(auditId, LlmExecutionCompletion.success(
                "fake-model-effective",
                COMPLETED_AT,
                3_000,
                new LlmTokenUsage(1_200, 340, 1_540),
                new LlmCostEstimate(new BigDecimal("0.001234"), "USD"),
                true
        ));

        assertThat(applied).isTrue();
        LlmExecutionAudit audit = repository.findById(auditId).orElseThrow();
        assertThat(audit.getStatus()).isEqualTo(LlmExecutionStatus.SUCCEEDED);
        assertThat(audit.getModel()).isEqualTo("fake-model-effective");
        assertThat(audit.getCompletedAt()).isEqualTo(COMPLETED_AT);
        assertThat(audit.getLatencyMs()).isEqualTo(3_000);
        assertThat(audit.getErrorCategory()).isNull();
        assertThat(audit.getInputTokens()).isEqualTo(1_200);
        assertThat(audit.getOutputTokens()).isEqualTo(340);
        assertThat(audit.getTotalTokens()).isEqualTo(1_540);
        assertThat(audit.getEstimatedCost()).isEqualByComparingTo(new BigDecimal("0.001234"));
        assertThat(audit.getCostCurrency()).isEqualTo("USD");
        assertThat(audit.isFallbackUsed()).isTrue();
    }

    @Test
    void persistsSuccessfulCompletionWithoutTokenUsageOrCostAsNulls() {
        UUID auditId = recorder.recordStart(start(UUID.randomUUID(), UUID.randomUUID(), null));

        recorder.complete(auditId, LlmExecutionCompletion.success(
                "fake-model", COMPLETED_AT, 900, null, null, false));

        LlmExecutionAudit audit = repository.findById(auditId).orElseThrow();
        assertThat(audit.getInputTokens()).isNull();
        assertThat(audit.getOutputTokens()).isNull();
        assertThat(audit.getTotalTokens()).isNull();
        assertThat(audit.getEstimatedCost()).isNull();
        assertThat(audit.getCostCurrency()).isNull();
    }

    @Test
    void persistsFailedCompletionWithStableCategoryOnly() {
        UUID auditId = recorder.recordStart(start(UUID.randomUUID(), UUID.randomUUID(), null));

        recorder.complete(auditId, LlmExecutionCompletion.failure(
                LlmExecutionStatus.FAILED,
                LlmErrorCategory.PROVIDER_REQUEST_REJECTED,
                "fake-model",
                COMPLETED_AT,
                150
        ));

        LlmExecutionAudit audit = repository.findById(auditId).orElseThrow();
        assertThat(audit.getStatus()).isEqualTo(LlmExecutionStatus.FAILED);
        assertThat(audit.getErrorCategory()).isEqualTo(LlmErrorCategory.PROVIDER_REQUEST_REJECTED);
        assertThat(audit.getInputTokens()).isNull();
        assertThat(audit.getEstimatedCost()).isNull();
    }

    @Test
    void persistsTimedOutCompletion() {
        UUID auditId = recorder.recordStart(start(UUID.randomUUID(), UUID.randomUUID(), null));

        recorder.complete(auditId, LlmExecutionCompletion.failure(
                LlmExecutionStatus.TIMED_OUT,
                LlmErrorCategory.PROVIDER_TIMEOUT,
                "fake-model",
                COMPLETED_AT,
                60_000
        ));

        LlmExecutionAudit audit = repository.findById(auditId).orElseThrow();
        assertThat(audit.getStatus()).isEqualTo(LlmExecutionStatus.TIMED_OUT);
        assertThat(audit.getErrorCategory()).isEqualTo(LlmErrorCategory.PROVIDER_TIMEOUT);
    }

    @Test
    void recordedSuccessCannotBeReplacedByDelayedFailure() {
        UUID auditId = recorder.recordStart(start(UUID.randomUUID(), UUID.randomUUID(), null));
        recorder.complete(auditId, LlmExecutionCompletion.success(
                "fake-model", COMPLETED_AT, 900, new LlmTokenUsage(10, 20, 30), null, false));

        boolean delayedFailureApplied = recorder.complete(auditId, LlmExecutionCompletion.failure(
                LlmExecutionStatus.TIMED_OUT,
                LlmErrorCategory.PROVIDER_TIMEOUT,
                "fake-model",
                COMPLETED_AT.plusMinutes(1),
                61_000
        ));

        assertThat(delayedFailureApplied).isFalse();
        LlmExecutionAudit audit = repository.findById(auditId).orElseThrow();
        assertThat(audit.getStatus()).isEqualTo(LlmExecutionStatus.SUCCEEDED);
        assertThat(audit.getErrorCategory()).isNull();
        assertThat(audit.getInputTokens()).isEqualTo(10);
        assertThat(audit.getLatencyMs()).isEqualTo(900);
    }

    @Test
    void duplicateCompletionDoesNotCorruptUsageOrStatus() {
        UUID auditId = recorder.recordStart(start(UUID.randomUUID(), UUID.randomUUID(), null));
        recorder.complete(auditId, LlmExecutionCompletion.success(
                "fake-model", COMPLETED_AT, 900, new LlmTokenUsage(10, 20, 30), null, false));

        boolean retryApplied = recorder.complete(auditId, LlmExecutionCompletion.success(
                "fake-model", COMPLETED_AT.plusSeconds(1), 1_900, new LlmTokenUsage(99, 99, 198), null, false));

        assertThat(retryApplied).isFalse();
        LlmExecutionAudit audit = repository.findById(auditId).orElseThrow();
        assertThat(audit.getInputTokens()).isEqualTo(10);
        assertThat(audit.getOutputTokens()).isEqualTo(20);
        assertThat(audit.getTotalTokens()).isEqualTo(30);
        assertThat(audit.getLatencyMs()).isEqualTo(900);
    }

    @Test
    void tenantQueriesAreIsolatedPerTenant() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID auditA = recorder.recordStart(start(tenantA, UUID.randomUUID(), null));
        UUID auditB = recorder.recordStart(start(tenantB, UUID.randomUUID(), null));

        assertThat(repository.findByTenantIdOrderByStartedAtDesc(tenantA))
                .extracting(LlmExecutionAudit::getId)
                .containsExactly(auditA)
                .doesNotContain(auditB);
        assertThat(repository.findByTenantIdOrderByStartedAtDesc(tenantB))
                .extracting(LlmExecutionAudit::getId)
                .containsExactly(auditB);
    }

    private LlmExecutionStart start(UUID tenantId, UUID traceId, UUID resourceId) {
        return new LlmExecutionStart(
                tenantId,
                UUID.randomUUID(),
                LlmFeature.SCENE_ANALYSIS,
                "fake",
                "fake-model",
                "scene-analysis:v1",
                traceId,
                resourceId == null ? null : AuditResourceType.SCENE,
                resourceId,
                STARTED_AT
        );
    }
}
