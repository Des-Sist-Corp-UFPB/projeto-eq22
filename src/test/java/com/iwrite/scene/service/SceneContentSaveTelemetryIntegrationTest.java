package com.iwrite.scene.service;

import com.iwrite.common.exception.ConflictException;
import com.iwrite.observability.BusinessTelemetry;
import com.iwrite.observability.RecordingTelemetry;
import com.iwrite.scene.dto.SceneContentRequest;
import com.iwrite.scene.dto.SceneResponse;
import com.iwrite.sceneversion.entity.SceneVersionSource;
import com.iwrite.support.PostgresIntegrationTest;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the real scene-content-save flow against PostgreSQL with an in-memory
 * OpenTelemetry SDK swapped in for the global one, and asserts the spans and
 * metrics that reach the exporter.
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
class SceneContentSaveTelemetryIntegrationTest extends PostgresIntegrationTest {

    private static final String PRIVATE_TEXT = "A porta se abriu devagar e o quarto prendeu a respiracao.";

    @TestConfiguration
    static class RecordingTelemetryConfiguration {

        @Bean
        RecordingTelemetry recordingTelemetry() {
            return new RecordingTelemetry();
        }

        @Bean
        BusinessTelemetry businessTelemetry(RecordingTelemetry recordingTelemetry) {
            return recordingTelemetry.telemetry();
        }
    }

    @Autowired
    private RecordingTelemetry recording;

    @BeforeEach
    void resetSpans() {
        recording.reset();
    }

    @Test
    void changedContentIsReportedAsSuccessWithControlledAttributes() {
        StoryWorld world = createStoryWorld("telemetry save success");

        sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest(
                        "{}",
                        PRIVATE_TEXT,
                        SceneVersionSource.MANUAL_SAVE,
                        world.scene().contentRevision(),
                        UUID.randomUUID()
                )
        );

        SpanData span = recording.span(BusinessTelemetry.SPAN_SCENE_CONTENT_SAVE);
        assertThat(span.getAttributes().get(BusinessTelemetry.OPERATION))
                .isEqualTo(BusinessTelemetry.OPERATION_SCENE_CONTENT_SAVE);
        assertThat(span.getAttributes().get(BusinessTelemetry.RESULT)).isEqualTo(BusinessTelemetry.RESULT_SUCCESS);
        assertThat(span.getAttributes().get(BusinessTelemetry.SCENE_SOURCE))
                .isEqualTo(BusinessTelemetry.SOURCE_MANUAL_SAVE);
        assertThat(span.getAttributes().get(BusinessTelemetry.SCENE_CONTENT_SIZE_BUCKET))
                .isEqualTo(BusinessTelemetry.BUCKET_SMALL);
        assertThat(span.getAttributes().get(BusinessTelemetry.SCENE_CONTENT_CHANGED)).isTrue();
        assertThat(recording.counterValue(
                BusinessTelemetry.OPERATION_SCENE_CONTENT_SAVE,
                BusinessTelemetry.RESULT_SUCCESS)).isPositive();
        assertThat(recording.durationCount(
                BusinessTelemetry.OPERATION_SCENE_CONTENT_SAVE,
                BusinessTelemetry.RESULT_SUCCESS)).isPositive();
        assertThat(attributeValues(span))
                .noneMatch(value -> value.contains(PRIVATE_TEXT))
                .noneMatch(value -> value.contains(world.scene().id().toString()))
                .noneMatch(value -> value.contains(world.book().id().toString()))
                .allMatch(value -> value.matches("(?i)true|false|[A-Za-z0-9][A-Za-z0-9._:/-]{0,63}"));
    }

    @Test
    void autosaveIsDistinguishedFromManualSave() {
        StoryWorld world = createStoryWorld("telemetry save autosave");

        sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest("{}", "autosaved words", SceneVersionSource.AUTO_SAVE,
                        world.scene().contentRevision(), UUID.randomUUID())
        );

        assertThat(recording.span(BusinessTelemetry.SPAN_SCENE_CONTENT_SAVE).getAttributes()
                .get(BusinessTelemetry.SCENE_SOURCE)).isEqualTo(BusinessTelemetry.SOURCE_AUTOSAVE);
    }

    @Test
    void unchangedContentIsReportedAsNoChange() {
        StoryWorld world = createStoryWorld("telemetry save unchanged");

        sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest(
                        world.scene().contentJson(),
                        world.scene().contentText(),
                        SceneVersionSource.MANUAL_SAVE,
                        world.scene().contentRevision(),
                        UUID.randomUUID()
                )
        );

        SpanData span = recording.span(BusinessTelemetry.SPAN_SCENE_CONTENT_SAVE);
        assertThat(span.getAttributes().get(BusinessTelemetry.RESULT)).isEqualTo(BusinessTelemetry.RESULT_NO_CHANGE);
        assertThat(span.getAttributes().get(BusinessTelemetry.SCENE_CONTENT_CHANGED)).isFalse();
        assertThat(recording.counterValue(
                BusinessTelemetry.OPERATION_SCENE_CONTENT_SAVE,
                BusinessTelemetry.RESULT_NO_CHANGE)).isPositive();
    }

    @Test
    void retryWithTheSameOperationIdIsReportedAsIdempotentRetry() {
        StoryWorld world = createStoryWorld("telemetry save idempotent");
        SceneContentRequest request = new SceneContentRequest(
                "{}",
                "one two three",
                SceneVersionSource.AUTO_SAVE,
                world.scene().contentRevision(),
                UUID.randomUUID()
        );

        SceneResponse first = sceneService.updateContent(world.scene().id(), request);
        SceneResponse retry = sceneService.updateContent(world.scene().id(), request);

        assertThat(retry.contentRevision()).isEqualTo(first.contentRevision());
        List<SpanData> spans = recording.spans();
        assertThat(spans).hasSize(2);
        assertThat(spans.get(0).getAttributes().get(BusinessTelemetry.RESULT))
                .isEqualTo(BusinessTelemetry.RESULT_SUCCESS);
        assertThat(spans.get(1).getAttributes().get(BusinessTelemetry.RESULT))
                .isEqualTo(BusinessTelemetry.RESULT_IDEMPOTENT_RETRY);
        assertThat(spans.get(1).getAttributes().get(BusinessTelemetry.SCENE_CONTENT_CHANGED)).isFalse();
        assertThat(recording.counterValue(
                BusinessTelemetry.OPERATION_SCENE_CONTENT_SAVE,
                BusinessTelemetry.RESULT_IDEMPOTENT_RETRY)).isPositive();
    }

    @Test
    void staleRevisionIsReportedAsConflictWithoutTheRevisionNumbers() {
        StoryWorld world = createStoryWorld("telemetry save conflict");
        sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest("{}", "first", SceneVersionSource.MANUAL_SAVE,
                        world.scene().contentRevision(), UUID.randomUUID())
        );
        recording.reset();

        assertThatThrownBy(() -> sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest("{}", "second", SceneVersionSource.MANUAL_SAVE,
                        world.scene().contentRevision(), UUID.randomUUID())
        )).isInstanceOf(ConflictException.class);

        SpanData span = recording.span(BusinessTelemetry.SPAN_SCENE_CONTENT_SAVE);
        assertThat(span.getAttributes().get(BusinessTelemetry.RESULT)).isEqualTo(BusinessTelemetry.RESULT_CONFLICT);
        assertThat(span.getAttributes().get(BusinessTelemetry.ERROR_TYPE)).isEqualTo("ConflictException");
        assertThat(span.getEvents()).isEmpty();
        assertThat(attributeValues(span))
                .noneMatch(value -> value.contains("Reload the scene"))
                .noneMatch(value -> value.contains("second"));
        assertThat(recording.counterValue(
                BusinessTelemetry.OPERATION_SCENE_CONTENT_SAVE,
                BusinessTelemetry.RESULT_CONFLICT)).isPositive();
    }

    @Test
    void saveSpanNestsUnderTheActiveHttpSpan() {
        StoryWorld world = createStoryWorld("telemetry save nesting");
        Span httpSpan = recording.sdk().getTracer("test-http")
                .spanBuilder("PATCH /api/scenes/{sceneId}/content").startSpan();

        try (Scope ignored = httpSpan.makeCurrent()) {
            sceneService.updateContent(
                    world.scene().id(),
                    new SceneContentRequest("{}", "nested words", SceneVersionSource.MANUAL_SAVE,
                            world.scene().contentRevision(), UUID.randomUUID())
            );
        } finally {
            httpSpan.end();
        }

        SpanData span = recording.span(BusinessTelemetry.SPAN_SCENE_CONTENT_SAVE);
        assertThat(span.getParentSpanId()).isEqualTo(httpSpan.getSpanContext().getSpanId());
        assertThat(span.getTraceId()).isEqualTo(httpSpan.getSpanContext().getTraceId());
    }

    private static List<String> attributeValues(SpanData span) {
        return span.getAttributes().asMap().values().stream().map(String::valueOf).toList();
    }
}
