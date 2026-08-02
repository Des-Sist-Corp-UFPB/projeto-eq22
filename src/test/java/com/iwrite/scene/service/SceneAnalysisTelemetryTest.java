package com.iwrite.scene.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iwrite.export.service.TipTapPlainTextRenderer;
import com.iwrite.llm.gateway.LlmCallContext;
import com.iwrite.llm.gateway.LlmCallResult;
import com.iwrite.llm.gateway.LlmErrorClassifier;
import com.iwrite.llm.gateway.LlmExecutionException;
import com.iwrite.llm.gateway.LlmExecutionGateway;
import com.iwrite.llm.gateway.LlmProviderCall;
import com.iwrite.observability.BusinessTelemetry;
import com.iwrite.observability.RecordingTelemetry;
import com.iwrite.scene.ai.WritingAssistant;
import com.iwrite.scene.dto.SceneAnalysisRequest;
import com.iwrite.scene.dto.SceneAnalysisResponse;
import com.iwrite.scene.entity.Scene;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Exercises the real {@link SceneAnalysisService} against an in-memory SDK and
 * asserts the spans and metrics it actually produces.
 */
@ExtendWith(MockitoExtension.class)
class SceneAnalysisTelemetryTest {

    private static final String PRIVATE_TITLE = "Capitulo secreto de Bentinho";
    private static final String PRIVATE_TEXT = "A porta se abriu devagar e o quarto prendeu a respiracao.";
    private static final String PRIVATE_FOCUS = "melhore o ritmo do dialogo entre Capitu e Bentinho";

    @Mock
    private SceneService sceneService;

    @Mock
    private WritingAssistant writingAssistant;

    @Mock
    private LlmExecutionGateway llmExecutionGateway;

    private RecordingTelemetry recording;
    private SceneAnalysisService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        recording = new RecordingTelemetry();
        lenient().when(writingAssistant.provider()).thenReturn("openai");
        lenient().when(writingAssistant.model()).thenReturn("gpt-4o-mini");
        lenient().when(llmExecutionGateway.execute(any(), any())).thenAnswer(invocation -> {
            LlmProviderCall<SceneAnalysisResponse> providerCall = invocation.getArgument(1);
            try {
                return providerCall.call(new LlmCallContext(UUID.randomUUID())).value();
            } catch (LlmExecutionException alreadyClassified) {
                throw alreadyClassified;
            } catch (Exception failure) {
                var classification = new LlmErrorClassifier().classify(failure);
                throw new LlmExecutionException(
                        classification.status(),
                        classification.category(),
                        UUID.randomUUID(),
                        failure
                );
            }
        });
        service = new SceneAnalysisService(
                sceneService,
                writingAssistant,
                new TipTapPlainTextRenderer(new ObjectMapper()),
                new TestTransactionManager(),
                llmExecutionGateway,
                recording.telemetry()
        );
    }

    @AfterEach
    void tearDown() {
        recording.close();
    }

    @Test
    void successfulAnalysisEmitsSpanCounterAndDuration() {
        UUID sceneId = UUID.randomUUID();
        when(sceneService.getScene(sceneId)).thenReturn(scene(PRIVATE_TEXT));
        when(writingAssistant.analyzeScene(any())).thenReturn(analysis());

        service.analyze(sceneId, new SceneAnalysisRequest(PRIVATE_FOCUS));

        SpanData span = recording.span(BusinessTelemetry.SPAN_SCENE_ANALYSIS);
        assertThat(span.getAttributes().get(BusinessTelemetry.OPERATION))
                .isEqualTo(BusinessTelemetry.OPERATION_SCENE_ANALYSIS);
        assertThat(span.getAttributes().get(BusinessTelemetry.RESULT)).isEqualTo(BusinessTelemetry.RESULT_SUCCESS);
        assertThat(span.getAttributes().get(BusinessTelemetry.AI_FOCUS_PRESENT)).isTrue();
        assertThat(span.getAttributes().get(BusinessTelemetry.AI_INPUT_SIZE_BUCKET))
                .isEqualTo(BusinessTelemetry.BUCKET_SMALL);
        assertThat(span.getAttributes().get(BusinessTelemetry.AI_PROVIDER)).isEqualTo("openai");
        assertThat(span.getAttributes().get(BusinessTelemetry.AI_MODEL)).isEqualTo("gpt-4o-mini");
        assertThat(span.getAttributes().get(BusinessTelemetry.AI_FALLBACK_USED)).isFalse();
        assertThat(recording.counterValue(
                BusinessTelemetry.OPERATION_SCENE_ANALYSIS,
                BusinessTelemetry.RESULT_SUCCESS)).isEqualTo(1);
        assertThat(recording.durationCount(
                BusinessTelemetry.OPERATION_SCENE_ANALYSIS,
                BusinessTelemetry.RESULT_SUCCESS)).isEqualTo(1);
    }

    @Test
    void analysisSpanNestsUnderTheActiveHttpSpan() {
        UUID sceneId = UUID.randomUUID();
        when(sceneService.getScene(sceneId)).thenReturn(scene(PRIVATE_TEXT));
        when(writingAssistant.analyzeScene(any())).thenReturn(analysis());
        Span httpSpan = recording.sdk().getTracer("test-http")
                .spanBuilder("POST /api/scenes/{sceneId}/ai-analysis").startSpan();

        try (Scope ignored = httpSpan.makeCurrent()) {
            service.analyze(sceneId, null);
        } finally {
            httpSpan.end();
        }

        SpanData span = recording.span(BusinessTelemetry.SPAN_SCENE_ANALYSIS);
        assertThat(span.getParentSpanId()).isEqualTo(httpSpan.getSpanContext().getSpanId());
        assertThat(span.getTraceId()).isEqualTo(httpSpan.getSpanContext().getTraceId());
    }

    @Test
    void recordsFallbackAsABooleanOnly() {
        UUID sceneId = UUID.randomUUID();
        when(sceneService.getScene(sceneId)).thenReturn(scene(PRIVATE_TEXT));
        when(writingAssistant.analyzeScene(any())).thenReturn(analysis().withFallbackUsed().withModel("gpt-4o"));

        service.analyze(sceneId, null);

        SpanData span = recording.span(BusinessTelemetry.SPAN_SCENE_ANALYSIS);
        assertThat(span.getAttributes().get(BusinessTelemetry.AI_FALLBACK_USED)).isTrue();
        // the model attribute stays the configured one; no per-call model string is added
        assertThat(span.getAttributes().get(BusinessTelemetry.AI_MODEL)).isEqualTo("gpt-4o-mini");
        assertThat(span.getAttributes().asMap()).hasSize(7);
    }

    @Test
    void classifiesMissingSceneTextAsValidationError() {
        UUID sceneId = UUID.randomUUID();
        when(sceneService.getScene(sceneId)).thenReturn(scene("   "));

        assertThatThrownBy(() -> service.analyze(sceneId, null)).hasMessageContaining("textual content");

        SpanData span = recording.span(BusinessTelemetry.SPAN_SCENE_ANALYSIS);
        assertThat(span.getAttributes().get(BusinessTelemetry.RESULT))
                .isEqualTo(BusinessTelemetry.RESULT_VALIDATION_ERROR);
        assertThat(span.getAttributes().get(BusinessTelemetry.ERROR_TYPE)).isEqualTo("BadRequestException");
        assertThat(recording.counterValue(
                BusinessTelemetry.OPERATION_SCENE_ANALYSIS,
                BusinessTelemetry.RESULT_VALIDATION_ERROR)).isEqualTo(1);
    }

    @Test
    void classifiesProviderOutageAsProviderErrorWithoutLeakingTheMessage() {
        UUID sceneId = UUID.randomUUID();
        when(sceneService.getScene(sceneId)).thenReturn(scene(PRIVATE_TEXT));
        when(writingAssistant.analyzeScene(any()))
                .thenThrow(new ResourceAccessException("connect timed out to https://api.openai.invalid key=sk-secret"));

        assertThatThrownBy(() -> service.analyze(sceneId, null)).isInstanceOf(LlmExecutionException.class);

        SpanData span = recording.span(BusinessTelemetry.SPAN_SCENE_ANALYSIS);
        assertThat(span.getAttributes().get(BusinessTelemetry.RESULT))
                .isEqualTo(BusinessTelemetry.RESULT_PROVIDER_ERROR);
        assertThat(span.getAttributes().get(BusinessTelemetry.ERROR_TYPE)).isEqualTo("LlmExecutionException");
        assertThat(span.getEvents()).isEmpty();
        assertThat(attributeValues(span)).noneMatch(value -> value.contains("sk-secret"))
                .noneMatch(value -> value.contains("api.openai.invalid"));
    }

    @Test
    void classifiesUnusableModelOutputAsInvalidResponse() {
        UUID sceneId = UUID.randomUUID();
        when(sceneService.getScene(sceneId)).thenReturn(scene(PRIVATE_TEXT));
        when(writingAssistant.analyzeScene(any()))
                .thenReturn(LlmCallResult.of(new SceneAnalysisResponse(null, null, null, null, null, null)));

        assertThatThrownBy(() -> service.analyze(sceneId, null)).isInstanceOf(LlmExecutionException.class);

        assertThat(recording.span(BusinessTelemetry.SPAN_SCENE_ANALYSIS).getAttributes()
                .get(BusinessTelemetry.RESULT)).isEqualTo(BusinessTelemetry.RESULT_INVALID_RESPONSE);
    }

    @Test
    void reportsOnlyTheTruncatedBucketForALongScene() {
        UUID sceneId = UUID.randomUUID();
        when(sceneService.getScene(sceneId)).thenReturn(scene("palavra ".repeat(2_000)));
        when(writingAssistant.analyzeScene(any())).thenReturn(analysis());

        service.analyze(sceneId, null);

        assertThat(recording.span(BusinessTelemetry.SPAN_SCENE_ANALYSIS).getAttributes()
                .get(BusinessTelemetry.AI_INPUT_SIZE_BUCKET)).isEqualTo(BusinessTelemetry.BUCKET_TRUNCATED);
    }

    @Test
    void neverPutsSceneContentTitleFocusOrIdentifiersInTelemetry() {
        UUID sceneId = UUID.randomUUID();
        Scene scene = scene(PRIVATE_TEXT);
        scene.setTitle(PRIVATE_TITLE);
        when(sceneService.getScene(sceneId)).thenReturn(scene);
        when(writingAssistant.analyzeScene(any())).thenReturn(analysis());

        service.analyze(sceneId, new SceneAnalysisRequest(PRIVATE_FOCUS));

        SpanData span = recording.span(BusinessTelemetry.SPAN_SCENE_ANALYSIS);
        assertThat(span.getName()).isEqualTo(BusinessTelemetry.SPAN_SCENE_ANALYSIS);
        assertThat(attributeValues(span))
                .isNotEmpty()
                .noneMatch(value -> value.contains(PRIVATE_TEXT))
                .noneMatch(value -> value.contains(PRIVATE_TITLE))
                .noneMatch(value -> value.contains(PRIVATE_FOCUS))
                .noneMatch(value -> value.contains("A tense entrance"))
                .noneMatch(value -> value.contains(sceneId.toString()))
                .allMatch(value -> value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,63}"));
        assertThat(recording.labels(BusinessTelemetry.METRIC_OPERATION_COUNT))
                .allSatisfy(labels -> assertThat(labels.asMap().values().stream().map(String::valueOf))
                        .noneMatch(value -> value.contains(sceneId.toString())));
    }

    private static List<String> attributeValues(SpanData span) {
        return span.getAttributes().asMap().values().stream().map(String::valueOf).toList();
    }

    private Scene scene(String contentText) {
        Scene scene = new Scene();
        scene.setContentJson("{\"type\":\"doc\"}");
        scene.setContentText(contentText);
        return scene;
    }

    private LlmCallResult<SceneAnalysisResponse> analysis() {
        return LlmCallResult.of(new SceneAnalysisResponse(
                "A tense entrance.",
                "Suspenseful",
                "Measured",
                List.of("Clear mood"),
                List.of("Conflict is still implied"),
                List.of("Clarify what is at stake")
        ));
    }

    private static class TestTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
