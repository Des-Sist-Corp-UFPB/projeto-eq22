package com.iwrite.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
            operation.attribute(BusinessTelemetry.AI_MODEL, UUID.randomUUID().toString());
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
            operation.attribute(BusinessTelemetry.AI_MODEL, "gpt-4o-mini")
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
}
