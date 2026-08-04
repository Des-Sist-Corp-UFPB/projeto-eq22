package com.iwrite.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessTelemetryTest {

    private final RecordingTelemetry recording = new RecordingTelemetry();

    @AfterEach
    void tearDown() {
        recording.close();
    }

    @Test
    void producesSpanCounterAndDurationForASuccessfulOperation() {
        try (BusinessTelemetry.Operation operation = recording.telemetry().sceneContentSave()) {
            operation.attribute(BusinessTelemetry.SCENE_SOURCE, BusinessTelemetry.SOURCE_MANUAL_SAVE);
        }

        SpanData span = recording.span(BusinessTelemetry.SPAN_SCENE_CONTENT_SAVE);
        assertThat(span.getAttributes().get(BusinessTelemetry.OPERATION))
                .isEqualTo(BusinessTelemetry.OPERATION_SCENE_CONTENT_SAVE);
        assertThat(span.getAttributes().get(BusinessTelemetry.RESULT)).isEqualTo(BusinessTelemetry.RESULT_SUCCESS);
        assertThat(recording.counterValue(
                BusinessTelemetry.OPERATION_SCENE_CONTENT_SAVE,
                BusinessTelemetry.RESULT_SUCCESS)).isEqualTo(1);
        assertThat(recording.durationCount(
                BusinessTelemetry.OPERATION_SCENE_CONTENT_SAVE,
                BusinessTelemetry.RESULT_SUCCESS)).isEqualTo(1);
        assertThat(recording.durationSum(
                BusinessTelemetry.OPERATION_SCENE_CONTENT_SAVE,
                BusinessTelemetry.RESULT_SUCCESS)).isGreaterThanOrEqualTo(0.0);
        assertThat(recording.durationUnit()).isEqualTo(BusinessTelemetry.DURATION_UNIT).isEqualTo("ms");
    }

    @Test
    void nestsUnderTheActiveParentSpan() {
        Tracer tracer = recording.sdk().getTracer("test-http");
        Span parent = tracer.spanBuilder("PATCH /api/scenes/{sceneId}/content").startSpan();
        try (Scope ignored = parent.makeCurrent()) {
            recording.telemetry().sceneContentSave().close();
            recording.telemetry().sceneAnalysis().close();
        } finally {
            parent.end();
        }

        assertThat(recording.span(BusinessTelemetry.SPAN_SCENE_CONTENT_SAVE).getParentSpanId())
                .isEqualTo(parent.getSpanContext().getSpanId());
        assertThat(recording.span(BusinessTelemetry.SPAN_SCENE_ANALYSIS).getParentSpanId())
                .isEqualTo(parent.getSpanContext().getSpanId());
        assertThat(recording.span(BusinessTelemetry.SPAN_SCENE_ANALYSIS).getTraceId())
                .isEqualTo(parent.getSpanContext().getTraceId());
    }

    @Test
    void separatesSuccessFromFailureInSpanAndMetrics() {
        recording.telemetry().sceneContentSave().close();
        try (BusinessTelemetry.Operation operation = recording.telemetry().sceneContentSave()) {
            operation.failure(BusinessTelemetry.RESULT_CONFLICT, new IllegalStateException("stale revision 41 vs 42"));
        }

        List<SpanData> spans = recording.spans();
        assertThat(spans).hasSize(2);
        assertThat(spans.get(0).getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
        assertThat(spans.get(1).getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(spans.get(1).getAttributes().get(BusinessTelemetry.RESULT))
                .isEqualTo(BusinessTelemetry.RESULT_CONFLICT);
        assertThat(recording.counterValue(
                BusinessTelemetry.OPERATION_SCENE_CONTENT_SAVE,
                BusinessTelemetry.RESULT_SUCCESS)).isEqualTo(1);
        assertThat(recording.counterValue(
                BusinessTelemetry.OPERATION_SCENE_CONTENT_SAVE,
                BusinessTelemetry.RESULT_CONFLICT)).isEqualTo(1);
    }

    @Test
    void keepsOnlyTheExceptionClassNameAndNeverItsMessage() {
        try (BusinessTelemetry.Operation operation = recording.telemetry().sceneAnalysis()) {
            operation.failure(
                    BusinessTelemetry.RESULT_PROVIDER_ERROR,
                    new IllegalStateException("401 from https://api.example.invalid key=sk-secret")
            );
        }

        SpanData span = recording.span(BusinessTelemetry.SPAN_SCENE_ANALYSIS);
        assertThat(span.getAttributes().get(BusinessTelemetry.ERROR_TYPE)).isEqualTo("IllegalStateException");
        assertThat(span.getEvents()).isEmpty();
        assertThat(span.getAttributes().asMap().values())
                .noneMatch(value -> String.valueOf(value).contains("sk-secret"))
                .noneMatch(value -> String.valueOf(value).contains("api.example.invalid"));
        assertThat(span.getStatus().getDescription()).isEmpty();
    }

    @Test
    void dropsAttributeValuesThatCouldCarryUserData() {
        try (BusinessTelemetry.Operation operation = recording.telemetry().sceneAnalysis()) {
            operation.attribute(BusinessTelemetry.AI_MODEL_FAMILY, UUID.randomUUID().toString());
            operation.attribute(BusinessTelemetry.AI_PROVIDER, "writer@example.com");
            operation.attribute(BusinessTelemetry.AI_INPUT_SIZE_BUCKET, "A porta se abriu devagar.");
            operation.attribute(BusinessTelemetry.SCENE_SOURCE, "Bearer sk-secret-token");
            operation.attribute(BusinessTelemetry.ERROR_TYPE, "a".repeat(65));
        }

        assertThat(recording.span(BusinessTelemetry.SPAN_SCENE_ANALYSIS).getAttributes().asMap().keySet())
                .containsExactlyInAnyOrder(BusinessTelemetry.OPERATION, BusinessTelemetry.RESULT);
    }

    @Test
    void dropsAttributesWhoseKeyIsNotAllowed() {
        try (BusinessTelemetry.Operation operation = recording.telemetry().sceneContentSave()) {
            operation.attribute(AttributeKey.stringKey("iwrite.scene.title"), "manuscrito");
            operation.attribute(AttributeKey.booleanKey("iwrite.tenant.admin"), true);
        }

        assertThat(recording.span(BusinessTelemetry.SPAN_SCENE_CONTENT_SAVE).getAttributes().asMap().keySet())
                .containsExactlyInAnyOrder(BusinessTelemetry.OPERATION, BusinessTelemetry.RESULT);
    }

    @Test
    void theGenericAttributeApiCanNeverOverwriteOperationOrResult() {
        String canary = "sk-test-canary";
        try (BusinessTelemetry.Operation operation = recording.telemetry().sceneContentSave()) {
            operation.attribute(BusinessTelemetry.OPERATION, canary);
            operation.attribute(BusinessTelemetry.RESULT, canary);
        }

        SpanData span = recording.span(BusinessTelemetry.SPAN_SCENE_CONTENT_SAVE);
        assertThat(span.getAttributes().get(BusinessTelemetry.OPERATION))
                .isEqualTo(BusinessTelemetry.OPERATION_SCENE_CONTENT_SAVE);
        assertThat(span.getAttributes().get(BusinessTelemetry.RESULT))
                .isEqualTo(BusinessTelemetry.RESULT_SUCCESS);
        assertThat(span.getAttributes().asMap().values().stream().map(String::valueOf))
                .noneMatch(value -> value.contains(canary));
        assertThat(recording.counterValue(
                BusinessTelemetry.OPERATION_SCENE_CONTENT_SAVE,
                BusinessTelemetry.RESULT_SUCCESS)).isEqualTo(1);
        assertThat(recording.labels(BusinessTelemetry.METRIC_OPERATION_COUNT))
                .allSatisfy(labels -> assertThat(labels.asMap().values().stream().map(String::valueOf))
                        .noneMatch(value -> value.contains(canary)));
    }

    @Test
    void normalizesResultsOutsideTheOperationVocabularyToFailure() {
        try (BusinessTelemetry.Operation operation = recording.telemetry().sceneContentSave()) {
            // valid for scene_analysis, not for scene_content_save
            operation.result(BusinessTelemetry.RESULT_PROVIDER_ERROR);
        }
        try (BusinessTelemetry.Operation operation = recording.telemetry().sceneAnalysis()) {
            operation.result("scene 3f2a of book Dom Casmurro");
        }

        assertThat(recording.span(BusinessTelemetry.SPAN_SCENE_CONTENT_SAVE).getAttributes()
                .get(BusinessTelemetry.RESULT)).isEqualTo(BusinessTelemetry.RESULT_FAILURE);
        assertThat(recording.span(BusinessTelemetry.SPAN_SCENE_ANALYSIS).getAttributes()
                .get(BusinessTelemetry.RESULT)).isEqualTo(BusinessTelemetry.RESULT_FAILURE);
    }

    @Test
    void publishesOnlyOperationAndResultAsMetricLabels() {
        try (BusinessTelemetry.Operation operation = recording.telemetry().sceneContentSave()) {
            operation.attribute(BusinessTelemetry.SCENE_SOURCE, BusinessTelemetry.SOURCE_AUTOSAVE)
                    .attribute(BusinessTelemetry.SCENE_CONTENT_SIZE_BUCKET, BusinessTelemetry.BUCKET_LARGE)
                    .attribute(BusinessTelemetry.SCENE_CONTENT_CHANGED, true);
        }
        try (BusinessTelemetry.Operation operation = recording.telemetry().sceneAnalysis()) {
            operation.attribute(BusinessTelemetry.AI_MODEL_FAMILY, BusinessTelemetry.MODEL_FAMILY_GPT_4O)
                    .failure(BusinessTelemetry.RESULT_PROVIDER_ERROR, new IllegalStateException("boom"));
        }

        for (String metric : List.of(
                BusinessTelemetry.METRIC_OPERATION_COUNT,
                BusinessTelemetry.METRIC_OPERATION_DURATION)) {
            assertThat(recording.labels(metric)).isNotEmpty().allSatisfy(labels ->
                    assertThat(labels.asMap().keySet())
                            .containsExactlyInAnyOrder(
                                    AttributeKey.stringKey("operation"),
                                    AttributeKey.stringKey("result")));
        }
    }

    @Test
    void bucketsSizesWithoutExposingTheExactLength() {
        assertThat(BusinessTelemetry.contentSizeBucket(null)).isEqualTo(BusinessTelemetry.BUCKET_EMPTY);
        assertThat(BusinessTelemetry.contentSizeBucket("")).isEqualTo(BusinessTelemetry.BUCKET_EMPTY);
        assertThat(BusinessTelemetry.contentSizeBucket("a")).isEqualTo(BusinessTelemetry.BUCKET_SMALL);
        assertThat(BusinessTelemetry.contentSizeBucket("a".repeat(2_000))).isEqualTo(BusinessTelemetry.BUCKET_MEDIUM);
        assertThat(BusinessTelemetry.contentSizeBucket("a".repeat(20_000))).isEqualTo(BusinessTelemetry.BUCKET_LARGE);
        assertThat(BusinessTelemetry.modelInputSizeBucket(12_000, true)).isEqualTo(BusinessTelemetry.BUCKET_TRUNCATED);
        assertThat(BusinessTelemetry.modelInputSizeBucket(10, false)).isEqualTo(BusinessTelemetry.BUCKET_SMALL);
    }

    @Test
    void modelFamilyNormalizesKnownPrefixesAndFallsBackSafely() {
        assertThat(BusinessTelemetry.modelFamily("gpt-4o")).isEqualTo(BusinessTelemetry.MODEL_FAMILY_GPT_4O);
        assertThat(BusinessTelemetry.modelFamily("gpt-4o-mini")).isEqualTo(BusinessTelemetry.MODEL_FAMILY_GPT_4O);
        assertThat(BusinessTelemetry.modelFamily("GPT-4O-MINI")).isEqualTo(BusinessTelemetry.MODEL_FAMILY_GPT_4O);
        assertThat(BusinessTelemetry.modelFamily("gpt-4.1")).isEqualTo(BusinessTelemetry.MODEL_FAMILY_GPT_4_1);
        assertThat(BusinessTelemetry.modelFamily("gpt-4.1-mini")).isEqualTo(BusinessTelemetry.MODEL_FAMILY_GPT_4_1);
        assertThat(BusinessTelemetry.modelFamily("gpt-5")).isEqualTo(BusinessTelemetry.MODEL_FAMILY_GPT_5);
        assertThat(BusinessTelemetry.modelFamily("gpt-5-mini")).isEqualTo(BusinessTelemetry.MODEL_FAMILY_GPT_5);
        assertThat(BusinessTelemetry.modelFamily("claude-opus-5")).isEqualTo(BusinessTelemetry.MODEL_FAMILY_OTHER);
        assertThat(BusinessTelemetry.modelFamily("modelo desconhecido")).isEqualTo(BusinessTelemetry.MODEL_FAMILY_OTHER);
        assertThat(BusinessTelemetry.modelFamily("sk-test-canary")).isEqualTo(BusinessTelemetry.MODEL_FAMILY_OTHER);
        assertThat(BusinessTelemetry.modelFamily(null)).isEqualTo(BusinessTelemetry.MODEL_FAMILY_UNKNOWN);
        assertThat(BusinessTelemetry.modelFamily("   ")).isEqualTo(BusinessTelemetry.MODEL_FAMILY_UNKNOWN);
    }

    @Test
    void everySceneSourceVocabularyValueIsAccepted() {
        for (String value : List.of(
                BusinessTelemetry.SOURCE_MANUAL_SAVE, BusinessTelemetry.SOURCE_AUTOSAVE,
                BusinessTelemetry.SOURCE_RESTORE, BusinessTelemetry.SOURCE_OTHER)) {
            recording.reset();
            recording.telemetry().sceneContentSave().attribute(BusinessTelemetry.SCENE_SOURCE, value).close();
            assertThat(recording.span(BusinessTelemetry.SPAN_SCENE_CONTENT_SAVE).getAttributes()
                    .get(BusinessTelemetry.SCENE_SOURCE)).as(value).isEqualTo(value);
        }
    }

    @Test
    void everySizeBucketVocabularyValueIsAccepted() {
        for (String value : List.of(
                BusinessTelemetry.BUCKET_EMPTY, BusinessTelemetry.BUCKET_SMALL, BusinessTelemetry.BUCKET_MEDIUM,
                BusinessTelemetry.BUCKET_LARGE, BusinessTelemetry.BUCKET_TRUNCATED)) {
            recording.reset();
            recording.telemetry().sceneContentSave()
                    .attribute(BusinessTelemetry.SCENE_CONTENT_SIZE_BUCKET, value)
                    .attribute(BusinessTelemetry.AI_INPUT_SIZE_BUCKET, value)
                    .close();
            Attributes attributes = recording.span(BusinessTelemetry.SPAN_SCENE_CONTENT_SAVE).getAttributes();
            assertThat(attributes.get(BusinessTelemetry.SCENE_CONTENT_SIZE_BUCKET)).as(value).isEqualTo(value);
            assertThat(attributes.get(BusinessTelemetry.AI_INPUT_SIZE_BUCKET)).as(value).isEqualTo(value);
        }
    }

    @Test
    void everyProviderVocabularyValueIsAccepted() {
        for (String value : List.of(BusinessTelemetry.PROVIDER_OPENAI, BusinessTelemetry.PROVIDER_DISABLED)) {
            recording.reset();
            recording.telemetry().sceneAnalysis().attribute(BusinessTelemetry.AI_PROVIDER, value).close();
            assertThat(recording.span(BusinessTelemetry.SPAN_SCENE_ANALYSIS).getAttributes()
                    .get(BusinessTelemetry.AI_PROVIDER)).as(value).isEqualTo(value);
        }
    }

    @Test
    void everyModelFamilyVocabularyValueIsAccepted() {
        for (String value : List.of(
                BusinessTelemetry.MODEL_FAMILY_GPT_4O, BusinessTelemetry.MODEL_FAMILY_GPT_4_1,
                BusinessTelemetry.MODEL_FAMILY_GPT_5, BusinessTelemetry.MODEL_FAMILY_OTHER,
                BusinessTelemetry.MODEL_FAMILY_UNKNOWN)) {
            recording.reset();
            recording.telemetry().sceneAnalysis().attribute(BusinessTelemetry.AI_MODEL_FAMILY, value).close();
            assertThat(recording.span(BusinessTelemetry.SPAN_SCENE_ANALYSIS).getAttributes()
                    .get(BusinessTelemetry.AI_MODEL_FAMILY)).as(value).isEqualTo(value);
        }
    }

    private static final List<String> CREDENTIAL_CANARIES = List.of(
            "sk-test-canary",
            "sk-proj-test-canary",
            "Bearer-test-canary",
            "ghp_test_canary",
            "github_pat_test_canary",
            "eyJhbGciOiJIUzI1NiJ9.test.signature",
            "email@example.com",
            UUID.randomUUID().toString(),
            "texto com espaço",
            "{\"key\":\"value\"}"
    );

    /**
     * Feeds every canary into every closed-vocabulary key plus the one
     * shape-filtered key ({@link BusinessTelemetry#ERROR_TYPE}), then
     * inspects the whole exported attribute set — not just the attribute
     * each canary targeted — so a leak through any key would be caught.
     */
    @Test
    void noCanaryEverReachesAnyExportedSpanAttribute() {
        for (String canary : CREDENTIAL_CANARIES) {
            recording.reset();
            try (BusinessTelemetry.Operation operation = recording.telemetry().sceneAnalysis()) {
                operation.attribute(BusinessTelemetry.AI_MODEL_FAMILY, canary)
                        .attribute(BusinessTelemetry.AI_PROVIDER, canary)
                        .attribute(BusinessTelemetry.SCENE_SOURCE, canary)
                        .attribute(BusinessTelemetry.SCENE_CONTENT_SIZE_BUCKET, canary)
                        .attribute(BusinessTelemetry.AI_INPUT_SIZE_BUCKET, canary)
                        .attribute(BusinessTelemetry.ERROR_TYPE, canary)
                        .attribute(BusinessTelemetry.OPERATION, canary)
                        .attribute(BusinessTelemetry.RESULT, canary);
            }

            List<String> exportedValues = recording.span(BusinessTelemetry.SPAN_SCENE_ANALYSIS)
                    .getAttributes().asMap().values().stream().map(String::valueOf).toList();
            assertThat(exportedValues).as("canary: %s", canary).noneMatch(value -> value.contains(canary));
        }
    }

    @Test
    void aCredentialShapedModelIsNeverExportedEvenThoughItLooksLikeAControlledValue() {
        try (BusinessTelemetry.Operation operation = recording.telemetry().sceneAnalysis()) {
            operation.attribute(BusinessTelemetry.AI_MODEL_FAMILY, BusinessTelemetry.modelFamily("sk-test-canary"));
        }

        SpanData span = recording.span(BusinessTelemetry.SPAN_SCENE_ANALYSIS);
        assertThat(span.getAttributes().get(BusinessTelemetry.AI_MODEL_FAMILY))
                .isEqualTo(BusinessTelemetry.MODEL_FAMILY_OTHER);
        assertThat(span.getAttributes().asMap().values().stream().map(String::valueOf))
                .noneMatch(value -> value.contains("sk-test-canary"));
    }

    @Test
    void staysNoOpWithoutAnAgentOrSdk() {
        BusinessTelemetry noSdk = new BusinessTelemetry(OpenTelemetry.noop());

        assertThatCode(() -> {
            try (BusinessTelemetry.Operation operation = noSdk.sceneAnalysis()) {
                assertThat(Span.current().getSpanContext().isValid()).isFalse();
                operation.attribute(BusinessTelemetry.AI_PROVIDER, "openai")
                        .result(BusinessTelemetry.RESULT_SUCCESS);
            }
        }).doesNotThrowAnyException();
        assertThat(recording.spans()).isEmpty();
    }

    @Test
    void ignoresRepeatedClose() {
        BusinessTelemetry.Operation operation = recording.telemetry().sceneContentSave();
        operation.close();
        operation.close();
        operation.attribute(BusinessTelemetry.SCENE_SOURCE, BusinessTelemetry.SOURCE_MANUAL_SAVE);

        assertThat(recording.spans()).hasSize(1);
        assertThat(recording.span(BusinessTelemetry.SPAN_SCENE_CONTENT_SAVE).getAttributes().asMap().keySet())
                .containsExactlyInAnyOrder(BusinessTelemetry.OPERATION, BusinessTelemetry.RESULT);
        assertThat(recording.counterValue(
                BusinessTelemetry.OPERATION_SCENE_CONTENT_SAVE,
                BusinessTelemetry.RESULT_SUCCESS)).isEqualTo(1);
    }

    @Test
    void restoresTheParentContextAfterTheOperationEnds() {
        Tracer tracer = recording.sdk().getTracer("test-http");
        Span parent = tracer.spanBuilder("PATCH /api/scenes/{sceneId}/content").startSpan();
        try (Scope ignored = parent.makeCurrent()) {
            recording.telemetry().sceneContentSave().close();
            assertThat(Span.current().getSpanContext().getSpanId()).isEqualTo(parent.getSpanContext().getSpanId());
        } finally {
            parent.end();
        }
    }

    @Test
    void deferredOperationStaysOpenThenClosesAsSuccessOnCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            BusinessTelemetry.Operation operation = recording.telemetry().sceneContentSave();

            assertThat(operation.deferEndToTransaction()).isTrue();
            assertThat(recording.spans()).isEmpty();

            completeTransaction(TransactionSynchronization.STATUS_COMMITTED);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        SpanData span = recording.span(BusinessTelemetry.SPAN_SCENE_CONTENT_SAVE);
        assertThat(span.getAttributes().get(BusinessTelemetry.RESULT)).isEqualTo(BusinessTelemetry.RESULT_SUCCESS);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
    }

    @Test
    void rollbackAfterTheBodyReturnedSuccessBecomesFailure() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            BusinessTelemetry.Operation operation = recording.telemetry().sceneContentSave();
            assertThat(operation.deferEndToTransaction()).isTrue();
            // no result() call: the body returned normally, which defaults to success.

            completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        SpanData span = recording.span(BusinessTelemetry.SPAN_SCENE_CONTENT_SAVE);
        assertThat(span.getAttributes().get(BusinessTelemetry.RESULT)).isEqualTo(BusinessTelemetry.RESULT_FAILURE);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    }

    @Test
    void rollbackAfterNoChangeOrIdempotentRetryAlsoBecomesFailure() {
        for (String preRollbackResult : List.of(
                BusinessTelemetry.RESULT_NO_CHANGE, BusinessTelemetry.RESULT_IDEMPOTENT_RETRY)) {
            recording.reset();
            TransactionSynchronizationManager.initSynchronization();
            try {
                BusinessTelemetry.Operation operation = recording.telemetry().sceneContentSave();
                assertThat(operation.deferEndToTransaction()).isTrue();
                operation.result(preRollbackResult);

                completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }

            assertThat(recording.span(BusinessTelemetry.SPAN_SCENE_CONTENT_SAVE).getAttributes()
                    .get(BusinessTelemetry.RESULT))
                    .as("pre-rollback result was %s", preRollbackResult)
                    .isEqualTo(BusinessTelemetry.RESULT_FAILURE);
        }
    }

    @Test
    void rollbackAfterAnAlreadyClassifiedConflictStaysConflict() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            BusinessTelemetry.Operation operation = recording.telemetry().sceneContentSave();
            assertThat(operation.deferEndToTransaction()).isTrue();
            operation.failure(BusinessTelemetry.RESULT_CONFLICT, new IllegalStateException("stale revision"));

            completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        SpanData span = recording.span(BusinessTelemetry.SPAN_SCENE_CONTENT_SAVE);
        assertThat(span.getAttributes().get(BusinessTelemetry.RESULT)).isEqualTo(BusinessTelemetry.RESULT_CONFLICT);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    }

    @Test
    void statusUnknownIsNeverReportedAsSuccess() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            BusinessTelemetry.Operation operation = recording.telemetry().sceneContentSave();
            assertThat(operation.deferEndToTransaction()).isTrue();

            completeTransaction(TransactionSynchronization.STATUS_UNKNOWN);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        SpanData span = recording.span(BusinessTelemetry.SPAN_SCENE_CONTENT_SAVE);
        assertThat(span.getAttributes().get(BusinessTelemetry.RESULT)).isEqualTo(BusinessTelemetry.RESULT_FAILURE);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    }

    @Test
    void withoutAnActiveTransactionTheCallerMustCloseItselfAndNothingLeaks() {
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();

        BusinessTelemetry.Operation operation = recording.telemetry().sceneContentSave();
        assertThat(operation.deferEndToTransaction()).isFalse();
        assertThat(recording.spans()).isEmpty();

        operation.close();

        assertThat(recording.spans()).hasSize(1);
        assertThat(recording.span(BusinessTelemetry.SPAN_SCENE_CONTENT_SAVE).getAttributes()
                .get(BusinessTelemetry.RESULT)).isEqualTo(BusinessTelemetry.RESULT_SUCCESS);
    }

    @Test
    void closeAfterTransactionCompletionRemainsIdempotent() {
        BusinessTelemetry.Operation operation = recording.telemetry().sceneContentSave();
        TransactionSynchronizationManager.initSynchronization();
        try {
            operation.deferEndToTransaction();
            completeTransaction(TransactionSynchronization.STATUS_COMMITTED);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        operation.close();
        operation.close();

        assertThat(recording.spans()).hasSize(1);
        assertThat(recording.counterValue(
                BusinessTelemetry.OPERATION_SCENE_CONTENT_SAVE,
                BusinessTelemetry.RESULT_SUCCESS)).isEqualTo(1);
    }

    @Test
    void aFailureRegisteringTheTransactionCallbackFallsBackWithoutBreakingTheOperationOrLeakingTheScope() {
        Tracer tracer = recording.sdk().getTracer("test-http");
        Span parent = tracer.spanBuilder("PATCH /api/scenes/{sceneId}/content").startSpan();
        boolean deferred;
        try (Scope ignored = parent.makeCurrent()) {
            BusinessTelemetry.Operation operation = recording.telemetry().sceneContentSave();
            try (MockedStatic<TransactionSynchronizationManager> mocked =
                         mockStatic(TransactionSynchronizationManager.class, CALLS_REAL_METHODS)) {
                mocked.when(TransactionSynchronizationManager::isSynchronizationActive).thenReturn(true);
                mocked.when(() -> TransactionSynchronizationManager.registerSynchronization(any()))
                        .thenThrow(new IllegalStateException("telemetry infrastructure failure"));

                deferred = operation.deferEndToTransaction();
            }
            operation.detachScope();
            if (!deferred) {
                operation.close();
            }

            assertThat(Span.current().getSpanContext().getSpanId())
                    .as("a failed deferral must still leave the scope detached, not leaked")
                    .isEqualTo(parent.getSpanContext().getSpanId());
        } finally {
            parent.end();
        }

        assertThat(deferred).isFalse();
        SpanData span = recording.span(BusinessTelemetry.SPAN_SCENE_CONTENT_SAVE);
        assertThat(span.getAttributes().get(BusinessTelemetry.RESULT)).isEqualTo(BusinessTelemetry.RESULT_SUCCESS);
    }

    @Test
    void detachScopeRestoresContextWithoutEndingTheSpanAndIsIdempotent() {
        Tracer tracer = recording.sdk().getTracer("test-http");
        Span parent = tracer.spanBuilder("PATCH /api/scenes/{sceneId}/content").startSpan();
        try (Scope ignored = parent.makeCurrent()) {
            BusinessTelemetry.Operation operation = recording.telemetry().sceneContentSave();

            operation.detachScope();
            assertThat(Span.current().getSpanContext().getSpanId()).isEqualTo(parent.getSpanContext().getSpanId());
            assertThat(recording.spans()).as("detachScope must not end the span").isEmpty();

            assertThatCode(operation::detachScope).doesNotThrowAnyException();
            assertThat(recording.spans()).isEmpty();

            operation.close();
            assertThat(recording.spans()).hasSize(1);

            assertThatCode(operation::close).doesNotThrowAnyException();
            assertThatCode(operation::detachScope).doesNotThrowAnyException();
            assertThat(recording.spans()).hasSize(1);
            assertThat(recording.counterValue(
                    BusinessTelemetry.OPERATION_SCENE_CONTENT_SAVE,
                    BusinessTelemetry.RESULT_SUCCESS)).isEqualTo(1);
        } finally {
            parent.end();
        }
    }

    /**
     * Two sequential deferred saves under the same external transaction and
     * parent span: each detaches its own scope right after its body runs,
     * so both end up siblings of the parent, never nested in each other, and
     * running their afterCompletion callbacks in registration order never
     * disturbs {@link Span#current()}.
     */
    @Test
    void deferredCompletionCallbacksNeverCorruptSpanCurrentForSequentialSaves() {
        Tracer tracer = recording.sdk().getTracer("test-http");
        Span parent = tracer.spanBuilder("PATCH /api/scenes/{sceneId}/content").startSpan();
        TransactionSynchronizationManager.initSynchronization();
        try (Scope ignored = parent.makeCurrent()) {
            BusinessTelemetry.Operation first = recording.telemetry().sceneContentSave();
            assertThat(first.deferEndToTransaction()).isTrue();
            first.detachScope();
            assertThat(Span.current().getSpanContext().getSpanId()).isEqualTo(parent.getSpanContext().getSpanId());

            BusinessTelemetry.Operation second = recording.telemetry().sceneContentSave();
            assertThat(second.deferEndToTransaction()).isTrue();
            second.detachScope();
            assertThat(Span.current().getSpanContext().getSpanId()).isEqualTo(parent.getSpanContext().getSpanId());

            assertThat(recording.spans())
                    .as("neither save span may be exported/ended before the transaction completes")
                    .isEmpty();

            completeTransaction(TransactionSynchronization.STATUS_COMMITTED);

            assertThat(Span.current().getSpanContext().getSpanId())
                    .as("running registered afterCompletion callbacks must not change what's current")
                    .isEqualTo(parent.getSpanContext().getSpanId());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            parent.end();
        }

        List<SpanData> saveSpans = recording.spans().stream()
                .filter(span -> span.getName().equals(BusinessTelemetry.SPAN_SCENE_CONTENT_SAVE))
                .toList();
        assertThat(saveSpans).hasSize(2);
        assertThat(saveSpans).allMatch(span -> span.getParentSpanId().equals(parent.getSpanContext().getSpanId()));
        assertThat(saveSpans.get(0).getSpanId()).isNotEqualTo(saveSpans.get(1).getParentSpanId());
        assertThat(saveSpans.get(1).getSpanId()).isNotEqualTo(saveSpans.get(0).getParentSpanId());
    }

    /**
     * The internal {@link Scope} is swapped for one whose {@code close()}
     * restores context correctly but then throws, simulating a misbehaving
     * agent. {@link BusinessTelemetry.Operation#detachScope()} must swallow
     * that failure, stay idempotent, and let {@code close()} still end the
     * span and record metrics exactly once.
     */
    @Test
    void aFailureClosingTheScopeNeverBreaksTheBusinessOperation() {
        Span mockSpan = mock(Span.class);
        when(mockSpan.setAttribute(any(AttributeKey.class), any())).thenReturn(mockSpan);
        Scope throwingScope = mock(Scope.class);
        doThrow(new IllegalStateException("scope close failed")).when(throwingScope).close();
        when(mockSpan.makeCurrent()).thenReturn(throwingScope);

        SpanBuilder mockSpanBuilder = mock(SpanBuilder.class);
        when(mockSpanBuilder.startSpan()).thenReturn(mockSpan);
        Tracer mockTracer = spanName -> mockSpanBuilder;

        OpenTelemetry mixed = new OpenTelemetry() {
            @Override
            public TracerProvider getTracerProvider() {
                return TracerProvider.noop();
            }

            @Override
            public Tracer getTracer(String instrumentationScopeName) {
                return mockTracer;
            }

            @Override
            public MeterProvider getMeterProvider() {
                return recording.sdk().getMeterProvider();
            }

            @Override
            public ContextPropagators getPropagators() {
                return recording.sdk().getPropagators();
            }
        };

        BusinessTelemetry telemetry = new BusinessTelemetry(mixed);
        BusinessTelemetry.Operation operation = telemetry.sceneContentSave();

        assertThatCode(operation::detachScope).doesNotThrowAnyException();
        assertThatCode(operation::detachScope).doesNotThrowAnyException();
        assertThatCode(operation::close).doesNotThrowAnyException();

        verify(throwingScope, times(1)).close();
        verify(mockSpan, times(1)).end();
    }

    private static void completeTransaction(int status) {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(status);
        }
    }
}
