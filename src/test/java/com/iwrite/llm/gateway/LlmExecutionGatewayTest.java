package com.iwrite.llm.gateway;

import com.iwrite.audit.entity.AuditResourceType;
import com.iwrite.llm.LlmFeature;
import com.iwrite.llm.LlmTokenUsage;
import com.iwrite.llm.audit.LlmErrorCategory;
import com.iwrite.llm.audit.LlmExecutionAuditRecorder;
import com.iwrite.llm.audit.LlmExecutionCompletion;
import com.iwrite.llm.audit.LlmExecutionStart;
import com.iwrite.llm.audit.LlmExecutionStatus;
import com.iwrite.llm.cost.ConfiguredLlmCostEstimator;
import com.iwrite.llm.cost.LlmPricingProperties;
import com.iwrite.user.context.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmExecutionGatewayTest {

    private static final UUID TENANT_ID = UUID.fromString("59000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("59000000-0000-0000-0000-000000000002");
    private static final Instant FIXED_INSTANT = Instant.parse("2026-07-02T12:00:00Z");
    private static final String MANUSCRIPT_SAMPLE = "Era uma vez um manuscrito completo da cena";
    private static final String API_KEY_SAMPLE = "sk-live-super-secret-key";

    private final LlmExecutionAuditRecorder auditRecorder = mock(LlmExecutionAuditRecorder.class);
    private final LlmExecutionGateway gateway = new LlmExecutionGateway(
            auditRecorder,
            new LlmErrorClassifier(),
            new ConfiguredLlmCostEstimator(pricing()),
            fixedIdentity(),
            Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)
    );

    @Test
    void successfulExecutionReturnsValueAndPersistsCompleteMetadata() {
        UUID auditId = stubStartedAudit();
        when(auditRecorder.complete(eq(auditId), any())).thenReturn(true);

        String value = gateway.execute(spec(), context -> LlmCallResult.of("analysis")
                .withTokenUsage(new LlmTokenUsage(100, 50, 150))
                .withModel("fake-model"));

        assertThat(value).isEqualTo("analysis");

        LlmExecutionStart start = capturedStart();
        assertThat(start.tenantId()).isEqualTo(TENANT_ID);
        assertThat(start.userId()).isEqualTo(USER_ID);
        assertThat(start.feature()).isEqualTo(LlmFeature.SCENE_ANALYSIS);
        assertThat(start.provider()).isEqualTo("fake");
        assertThat(start.promptVersion()).isEqualTo("scene-analysis:v1");
        assertThat(start.startedAt()).isEqualTo(OffsetDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));
        assertThat(start.traceId()).isNotNull();

        LlmExecutionCompletion completion = capturedCompletion(auditId);
        assertThat(completion.status()).isEqualTo(LlmExecutionStatus.SUCCEEDED);
        assertThat(completion.model()).isEqualTo("fake-model");
        assertThat(completion.errorCategory()).isNull();
        assertThat(completion.tokenUsage()).isEqualTo(new LlmTokenUsage(100, 50, 150));
        assertThat(completion.estimatedCost().amount()).isEqualByComparingTo(new BigDecimal("0.000400"));
        assertThat(completion.estimatedCost().currency()).isEqualTo("USD");
        assertThat(completion.fallbackUsed()).isFalse();
        assertThat(completion.latencyMs()).isNotNegative();
    }

    @Test
    void absentTokenUsageIsPersistedAsNullWithoutCost() {
        UUID auditId = stubStartedAudit();
        when(auditRecorder.complete(eq(auditId), any())).thenReturn(true);

        gateway.execute(spec(), context -> LlmCallResult.of("analysis"));

        LlmExecutionCompletion completion = capturedCompletion(auditId);
        assertThat(completion.tokenUsage()).isNull();
        assertThat(completion.estimatedCost()).isNull();
    }

    @Test
    void missingPricingConfigurationPersistsNullCostForReportedUsage() {
        UUID auditId = stubStartedAudit();
        when(auditRecorder.complete(eq(auditId), any())).thenReturn(true);

        gateway.execute(spec(), context -> LlmCallResult.of("analysis")
                .withTokenUsage(new LlmTokenUsage(100, 50, 150))
                .withModel("model-without-pricing"));

        LlmExecutionCompletion completion = capturedCompletion(auditId);
        assertThat(completion.tokenUsage()).isNotNull();
        assertThat(completion.estimatedCost()).isNull();
    }

    @Test
    void valid128CharacterProviderModelIsAccepted() {
        UUID auditId = stubStartedAudit();
        when(auditRecorder.complete(eq(auditId), any())).thenReturn(true);
        String providerModel = "m".repeat(128);

        gateway.execute(spec(), context -> LlmCallResult.of("analysis").withModel(providerModel));

        LlmExecutionCompletion completion = capturedCompletion(auditId);
        assertThat(completion.status()).isEqualTo(LlmExecutionStatus.SUCCEEDED);
        assertThat(completion.model()).isEqualTo(providerModel);
    }

    @Test
    void providerModelAbove128CharactersFallsBackToSpecModelAndCompletesAudit() {
        UUID auditId = stubStartedAudit();
        when(auditRecorder.complete(eq(auditId), any())).thenReturn(true);

        gateway.execute(spec(), context -> LlmCallResult.of("analysis").withModel("m".repeat(129)));

        LlmExecutionCompletion completion = capturedCompletion(auditId);
        assertThat(completion.status()).isEqualTo(LlmExecutionStatus.SUCCEEDED);
        assertThat(completion.status()).isNotEqualTo(LlmExecutionStatus.STARTED);
        assertThat(completion.model()).isEqualTo(spec().model());
    }

    @Test
    void invalidFormatProviderModelFallsBackToSpecModel() {
        UUID auditId = stubStartedAudit();
        when(auditRecorder.complete(eq(auditId), any())).thenReturn(true);

        gateway.execute(spec(), context -> LlmCallResult.of("analysis").withModel("invalid model"));

        LlmExecutionCompletion completion = capturedCompletion(auditId);
        assertThat(completion.status()).isEqualTo(LlmExecutionStatus.SUCCEEDED);
        assertThat(completion.model()).isEqualTo(spec().model());
    }

    @Test
    void nullProviderModelUsesSpecModel() {
        UUID auditId = stubStartedAudit();
        when(auditRecorder.complete(eq(auditId), any())).thenReturn(true);

        gateway.execute(spec(), context -> LlmCallResult.of("analysis"));

        assertThat(capturedCompletion(auditId).model()).isEqualTo(spec().model());
    }

    @Test
    void fallbackIndicatorIsPersisted() {
        UUID auditId = stubStartedAudit();
        when(auditRecorder.complete(eq(auditId), any())).thenReturn(true);

        gateway.execute(spec(), context -> LlmCallResult.of("fallback analysis").withFallbackUsed());

        assertThat(capturedCompletion(auditId).fallbackUsed()).isTrue();
    }

    @Test
    void unavailableProviderIsClassifiedPersistedAndRethrownSafely() {
        UUID auditId = stubStartedAudit();
        when(auditRecorder.complete(eq(auditId), any())).thenReturn(true);

        assertThatThrownBy(() -> gateway.execute(spec(), context -> {
            throw new TransientAiException("HTTP 503 " + API_KEY_SAMPLE);
        }))
                .isInstanceOf(LlmExecutionException.class)
                .hasMessageNotContaining(API_KEY_SAMPLE)
                .satisfies(failure -> {
                    LlmExecutionException execution = (LlmExecutionException) failure;
                    assertThat(execution.getStatus()).isEqualTo(LlmExecutionStatus.UNAVAILABLE);
                    assertThat(execution.getErrorCategory()).isEqualTo(LlmErrorCategory.PROVIDER_UNAVAILABLE);
                });

        LlmExecutionCompletion completion = capturedCompletion(auditId);
        assertThat(completion.status()).isEqualTo(LlmExecutionStatus.UNAVAILABLE);
        assertThat(completion.errorCategory()).isEqualTo(LlmErrorCategory.PROVIDER_UNAVAILABLE);
        assertThat(completion.tokenUsage()).isNull();
        assertThat(completion.estimatedCost()).isNull();
    }

    @Test
    void providerTimeoutIsPersistedAsTimedOut() {
        UUID auditId = stubStartedAudit();
        when(auditRecorder.complete(eq(auditId), any())).thenReturn(true);

        assertThatThrownBy(() -> gateway.execute(spec(), context -> {
            throw new ResourceAccessException("I/O", new IOException(new SocketTimeoutException("read timed out")));
        }))
                .isInstanceOf(LlmExecutionException.class);

        LlmExecutionCompletion completion = capturedCompletion(auditId);
        assertThat(completion.status()).isEqualTo(LlmExecutionStatus.TIMED_OUT);
        assertThat(completion.errorCategory()).isEqualTo(LlmErrorCategory.PROVIDER_TIMEOUT);
    }

    @Test
    void invalidStructuredResponseIsPersistedAsInvalidResponse() {
        UUID auditId = stubStartedAudit();
        when(auditRecorder.complete(eq(auditId), any())).thenReturn(true);

        assertThatThrownBy(() -> gateway.execute(spec(), context -> {
            throw new LlmInvalidResponseException(new IllegalArgumentException("unparseable body"));
        }))
                .isInstanceOf(LlmExecutionException.class)
                .hasMessageNotContaining("unparseable body");

        assertThat(capturedCompletion(auditId).status()).isEqualTo(LlmExecutionStatus.INVALID_RESPONSE);
    }

    @Test
    void nullTypedValueIsTreatedAsInvalidResponse() {
        UUID auditId = stubStartedAudit();
        when(auditRecorder.complete(eq(auditId), any())).thenReturn(true);

        assertThatThrownBy(() -> gateway.execute(spec(), context -> LlmCallResult.of(null)))
                .isInstanceOf(LlmExecutionException.class);

        LlmExecutionCompletion completion = capturedCompletion(auditId);
        assertThat(completion.status()).isEqualTo(LlmExecutionStatus.INVALID_RESPONSE);
        assertThat(completion.errorCategory()).isEqualTo(LlmErrorCategory.INVALID_STRUCTURED_RESPONSE);
    }

    @Test
    void unexpectedExceptionIsPersistedAsInternalExecutionError() {
        UUID auditId = stubStartedAudit();
        when(auditRecorder.complete(eq(auditId), any())).thenReturn(true);

        assertThatThrownBy(() -> gateway.execute(spec(), context -> {
            throw new IllegalStateException(MANUSCRIPT_SAMPLE);
        }))
                .isInstanceOf(LlmExecutionException.class)
                .hasMessageNotContaining(MANUSCRIPT_SAMPLE);

        LlmExecutionCompletion completion = capturedCompletion(auditId);
        assertThat(completion.status()).isEqualTo(LlmExecutionStatus.FAILED);
        assertThat(completion.errorCategory()).isEqualTo(LlmErrorCategory.INTERNAL_EXECUTION_ERROR);
    }

    @Test
    void disabledFeatureIsPersistedAsDisabled() {
        UUID auditId = stubStartedAudit();
        when(auditRecorder.complete(eq(auditId), any())).thenReturn(true);

        assertThatThrownBy(() -> gateway.execute(spec(), context -> {
            throw new LlmFeatureDisabledException();
        }))
                .isInstanceOf(LlmExecutionException.class);

        LlmExecutionCompletion completion = capturedCompletion(auditId);
        assertThat(completion.status()).isEqualTo(LlmExecutionStatus.DISABLED);
        assertThat(completion.errorCategory()).isEqualTo(LlmErrorCategory.FEATURE_DISABLED);
    }

    @Test
    void startAuditPersistenceFailureAbortsBeforeProviderInvocation() {
        when(auditRecorder.recordStart(any())).thenThrow(new RuntimeException("database down"));
        AtomicBoolean providerInvoked = new AtomicBoolean(false);

        assertThatThrownBy(() -> gateway.execute(spec(), context -> {
            providerInvoked.set(true);
            return LlmCallResult.of("analysis");
        }))
                .isInstanceOf(LlmExecutionException.class)
                .satisfies(failure -> assertThat(((LlmExecutionException) failure).getErrorCategory())
                        .isEqualTo(LlmErrorCategory.AUDIT_PERSISTENCE_FAILURE));

        assertThat(providerInvoked).isFalse();
        verify(auditRecorder, never()).complete(any(), any());
    }

    @Test
    void completionPersistenceFailureAfterSuccessPreservesProductResult() {
        UUID auditId = stubStartedAudit();
        when(auditRecorder.complete(eq(auditId), any())).thenThrow(new RuntimeException("database down"));

        String value = gateway.execute(spec(), context -> LlmCallResult.of("analysis"));

        assertThat(value).isEqualTo("analysis");
    }

    @Test
    void completionPersistenceFailureAfterProviderFailureRethrowsOriginalClassification() {
        UUID auditId = stubStartedAudit();
        when(auditRecorder.complete(eq(auditId), any())).thenThrow(new RuntimeException("database down"));

        assertThatThrownBy(() -> gateway.execute(spec(), context -> {
            throw new TransientAiException("HTTP 503");
        }))
                .isInstanceOf(LlmExecutionException.class)
                .satisfies(failure -> assertThat(((LlmExecutionException) failure).getStatus())
                        .isEqualTo(LlmExecutionStatus.UNAVAILABLE));
    }

    @Test
    void alreadyFinalizedAuditDoesNotFailTheExecution() {
        UUID auditId = stubStartedAudit();
        when(auditRecorder.complete(eq(auditId), any())).thenReturn(false);

        String value = gateway.execute(spec(), context -> LlmCallResult.of("analysis"));

        assertThat(value).isEqualTo("analysis");
    }

    @Test
    void traceIdIsSharedBetweenAuditRowProviderCallAndMdc() {
        UUID auditId = stubStartedAudit();
        when(auditRecorder.complete(eq(auditId), any())).thenReturn(true);
        AtomicReference<UUID> contextTraceId = new AtomicReference<>();
        AtomicReference<String> mdcTraceId = new AtomicReference<>();

        gateway.execute(spec(), context -> {
            contextTraceId.set(context.traceId());
            mdcTraceId.set(MDC.get("llmTraceId"));
            return LlmCallResult.of("analysis");
        });

        UUID persistedTraceId = capturedStart().traceId();
        assertThat(contextTraceId.get()).isEqualTo(persistedTraceId);
        assertThat(mdcTraceId.get()).isEqualTo(persistedTraceId.toString());
        assertThat(MDC.get("llmTraceId")).isNull();
    }

    @Test
    void executionInsideActiveTransactionIsRejectedBeforeAnyPersistence() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> gateway.execute(spec(), context -> LlmCallResult.of("analysis")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must not run inside a database transaction");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        verify(auditRecorder, never()).recordStart(any());
        verify(auditRecorder, never()).complete(any(), any());
    }

    @Test
    void onlyBoundedSpecMetadataReachesPersistenceNeverContentOrSecrets() {
        UUID auditId = stubStartedAudit();
        when(auditRecorder.complete(eq(auditId), any())).thenReturn(true);

        assertThatThrownBy(() -> gateway.execute(spec(), context -> {
            throw new RuntimeException("prompt: Analyze scene. " + MANUSCRIPT_SAMPLE + " " + API_KEY_SAMPLE);
        }))
                .isInstanceOf(LlmExecutionException.class);

        LlmExecutionStart start = capturedStart();
        LlmExecutionCompletion completion = capturedCompletion(auditId);
        for (String persistedText : new String[]{
                start.provider(), start.model(), start.promptVersion(),
                completion.model(), String.valueOf(completion.errorCategory()), String.valueOf(completion.status())
        }) {
            assertThat(persistedText == null ? "" : persistedText)
                    .doesNotContain(MANUSCRIPT_SAMPLE)
                    .doesNotContain(API_KEY_SAMPLE)
                    .doesNotContain("prompt:");
        }
    }

    private LlmExecutionSpec spec() {
        return LlmExecutionSpec
                .of(LlmFeature.SCENE_ANALYSIS, "fake", "fake-model", "scene-analysis:v1")
                .withResource(AuditResourceType.SCENE, UUID.randomUUID());
    }

    private UUID stubStartedAudit() {
        UUID auditId = UUID.randomUUID();
        when(auditRecorder.recordStart(any())).thenReturn(auditId);
        return auditId;
    }

    private LlmExecutionStart capturedStart() {
        ArgumentCaptor<LlmExecutionStart> captor = ArgumentCaptor.forClass(LlmExecutionStart.class);
        verify(auditRecorder).recordStart(captor.capture());
        return captor.getValue();
    }

    private LlmExecutionCompletion capturedCompletion(UUID auditId) {
        ArgumentCaptor<LlmExecutionCompletion> captor = ArgumentCaptor.forClass(LlmExecutionCompletion.class);
        verify(auditRecorder).complete(eq(auditId), captor.capture());
        return captor.getValue();
    }

    private static CurrentUserProvider fixedIdentity() {
        return new CurrentUserProvider() {
            @Override
            public UUID userId() {
                return USER_ID;
            }

            @Override
            public UUID tenantId() {
                return TENANT_ID;
            }

            @Override
            public ZoneId effectiveZoneId() {
                return ZoneOffset.UTC;
            }
        };
    }

    private static LlmPricingProperties pricing() {
        LlmPricingProperties properties = new LlmPricingProperties();
        properties.setEnabled(true);
        properties.setCurrency("USD");
        LlmPricingProperties.ModelPricing modelPricing = new LlmPricingProperties.ModelPricing();
        modelPricing.setInputPerMillionTokens(new BigDecimal("2.00"));
        modelPricing.setOutputPerMillionTokens(new BigDecimal("4.00"));
        LlmPricingProperties.ProviderPricing providerPricing = new LlmPricingProperties.ProviderPricing();
        providerPricing.setModels(Map.of("fake-model", modelPricing));
        properties.setProviders(Map.of("fake", providerPricing));
        return properties;
    }
}
