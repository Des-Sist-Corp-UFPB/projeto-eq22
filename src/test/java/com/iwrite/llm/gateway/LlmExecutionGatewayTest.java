package com.iwrite.llm.gateway;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
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
import com.iwrite.observability.BusinessTelemetry;
import com.iwrite.observability.CapturedLogs;
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

    /**
     * A broken audit table must still be observable: before this fix, only a
     * textual log existed and no {@code iwrite.llm.execution} event was
     * emitted, so ERROR-based alerting on that event never fired for this
     * failure. The provider is never invoked (fail-closed) and no audit row
     * exists to finalize, so {@code auditRecorder.complete} must never be
     * called either.
     */
    @Test
    void startAuditPersistenceFailureAbortsBeforeProviderInvocationAndEmitsOneErrorEvent() {
        when(auditRecorder.recordStart(any())).thenThrow(new RuntimeException("database down " + API_KEY_SAMPLE));
        AtomicBoolean providerInvoked = new AtomicBoolean(false);

        try (CapturedLogs logs = new CapturedLogs()) {
            assertThatThrownBy(() -> gateway.execute(spec(), context -> {
                providerInvoked.set(true);
                return LlmCallResult.of("analysis");
            }))
                    .isInstanceOf(LlmExecutionException.class)
                    .hasMessageNotContaining(API_KEY_SAMPLE)
                    .satisfies(failure -> assertThat(((LlmExecutionException) failure).getErrorCategory())
                            .isEqualTo(LlmErrorCategory.AUDIT_PERSISTENCE_FAILURE));

            ILoggingEvent event = logs.single("iwrite.llm.execution");
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(CapturedLogs.keyValues(event))
                    .containsEntry("iwrite.llm.status", "FAILED")
                    .containsEntry("iwrite.error.category", "AUDIT_PERSISTENCE_FAILURE")
                    .containsEntry("iwrite.ai.fallback_used", false)
                    .containsEntry("iwrite.llm.input_tokens", null)
                    .containsEntry("iwrite.llm.output_tokens", null)
                    .containsEntry("iwrite.llm.total_tokens", null)
                    .doesNotContainKey("llmExecutionId");
            assertThat(CapturedLogs.keyValues(event).get(BusinessTelemetry.DURATION_MS_KEY))
                    .isInstanceOf(Long.class);
            assertThat(event.getMDCPropertyMap())
                    .as("llmExecutionId stays a local MDC entry, never an exported key-value pair")
                    .containsKey("llmExecutionId");
            assertThat(CapturedLogs.allSurfaces(event)).doesNotContain(API_KEY_SAMPLE);
        }

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

    /**
     * The audit UUID is an execution/audit identifier, never the OpenTelemetry
     * distributed {@code trace_id}, so its MDC key is named for what it is.
     */
    @Test
    void executionIdIsSharedBetweenAuditRowProviderCallAndMdc() {
        UUID auditId = stubStartedAudit();
        when(auditRecorder.complete(eq(auditId), any())).thenReturn(true);
        AtomicReference<UUID> contextTraceId = new AtomicReference<>();
        AtomicReference<String> mdcExecutionId = new AtomicReference<>();

        gateway.execute(spec(), context -> {
            contextTraceId.set(context.traceId());
            mdcExecutionId.set(MDC.get("llmExecutionId"));
            return LlmCallResult.of("analysis");
        });

        UUID persistedTraceId = capturedStart().traceId();
        assertThat(contextTraceId.get()).isEqualTo(persistedTraceId);
        assertThat(mdcExecutionId.get()).isEqualTo(persistedTraceId.toString());
        assertThat(MDC.get("llmExecutionId")).isNull();
        assertThat(MDC.get("llmTraceId")).isNull();
    }

    /**
     * The structured outcome event carries only controlled vocabularies. The
     * configured model is the dangerous one: {@link LlmExecutionSpec} accepts
     * any short identifier, so a credential placed in {@code OPENAI_MODEL}
     * would pass validation — only its model family may reach the event.
     */
    @Test
    void outcomeEventCarriesOnlyControlledMetadataNeverTheRawModel() {
        UUID auditId = stubStartedAudit();
        when(auditRecorder.complete(eq(auditId), any())).thenReturn(true);

        try (CapturedLogs logs = new CapturedLogs()) {
            gateway.execute(
                    LlmExecutionSpec.of(LlmFeature.SCENE_ANALYSIS, "fake", API_KEY_SAMPLE, "scene-analysis:v1"),
                    context -> LlmCallResult.of("analysis").withTokenUsage(new LlmTokenUsage(10, 5, 15))
            );

            ILoggingEvent event = logs.single("iwrite.llm.execution");
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(CapturedLogs.keyValues(event))
                    .containsEntry("iwrite.llm.feature", "SCENE_ANALYSIS")
                    .containsEntry("iwrite.ai.provider", "unknown")
                    .containsEntry("iwrite.ai.model_family", "other")
                    .containsEntry("iwrite.llm.prompt_version", "scene-analysis:v1")
                    .containsEntry("iwrite.llm.status", "SUCCEEDED")
                    .containsEntry("iwrite.llm.total_tokens", 15)
                    .doesNotContainKey("iwrite.llm.model")
                    .doesNotContainKey("iwrite.llm.execution_id");
            assertThat(CapturedLogs.allSurfaces(event))
                    .doesNotContain(API_KEY_SAMPLE)
                    .doesNotContain(MANUSCRIPT_SAMPLE)
                    .doesNotContain(TENANT_ID.toString())
                    .doesNotContain(USER_ID.toString());
        }
    }

    /**
     * A provider timeout and a broken deployment must not share a severity, or
     * ERROR-based alerting would never fire for the second one.
     */
    @Test
    void unexpectedInternalFailureIsErrorWhileExpectedProviderFailureStaysWarn() {
        UUID auditId = stubStartedAudit();
        when(auditRecorder.complete(eq(auditId), any())).thenReturn(true);

        try (CapturedLogs logs = new CapturedLogs()) {
            assertThatThrownBy(() -> gateway.execute(spec(), context -> {
                throw new IllegalStateException("unrecognized " + API_KEY_SAMPLE);
            })).isInstanceOf(LlmExecutionException.class);

            ILoggingEvent internal = logs.single("iwrite.llm.execution");
            assertThat(internal.getLevel()).isEqualTo(Level.ERROR);
            assertThat(CapturedLogs.keyValues(internal))
                    .containsEntry("iwrite.error.category", "INTERNAL_EXECUTION_ERROR");
            assertThat(CapturedLogs.allSurfaces(internal)).doesNotContain(API_KEY_SAMPLE);
        }

        UUID retryAuditId = stubStartedAudit();
        when(auditRecorder.complete(eq(retryAuditId), any())).thenReturn(true);
        try (CapturedLogs logs = new CapturedLogs()) {
            assertThatThrownBy(() -> gateway.execute(spec(), context -> {
                throw new TransientAiException("HTTP 503");
            })).isInstanceOf(LlmExecutionException.class);

            ILoggingEvent expected = logs.single("iwrite.llm.execution");
            assertThat(expected.getLevel()).isEqualTo(Level.WARN);
            assertThat(CapturedLogs.keyValues(expected))
                    .containsEntry("iwrite.error.category", "PROVIDER_UNAVAILABLE");
        }
    }

    @Test
    void failedOutcomeEventIsWarnAndCarriesNoProviderMessage() {
        UUID auditId = stubStartedAudit();
        when(auditRecorder.complete(eq(auditId), any())).thenReturn(true);

        try (CapturedLogs logs = new CapturedLogs()) {
            assertThatThrownBy(() -> gateway.execute(spec(), context -> {
                throw new TransientAiException("provider exception with secret " + API_KEY_SAMPLE);
            })).isInstanceOf(LlmExecutionException.class);

            ILoggingEvent event = logs.single("iwrite.llm.execution");
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(CapturedLogs.keyValues(event))
                    .containsEntry("iwrite.llm.status", "UNAVAILABLE")
                    .containsEntry("iwrite.error.category", "PROVIDER_UNAVAILABLE");
            assertThat(CapturedLogs.allSurfaces(event))
                    .doesNotContain(API_KEY_SAMPLE)
                    .doesNotContain("provider exception with secret");
        }
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
