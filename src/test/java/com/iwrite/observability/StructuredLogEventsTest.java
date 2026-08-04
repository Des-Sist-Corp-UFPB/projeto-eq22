package com.iwrite.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import com.iwrite.audit.entity.AuditAction;
import com.iwrite.audit.entity.AuditResourceType;
import com.iwrite.audit.service.AuditLogService;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.llm.audit.LlmErrorCategory;
import com.iwrite.llm.audit.LlmExecutionStatus;
import com.iwrite.llm.gateway.LlmExecutionException;
import com.iwrite.mcp.McpInvocationSupport;
import com.iwrite.mcp.McpToolException;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Contract for the structured log events exported to Loki through the
 * OpenTelemetry Java Agent's Logback instrumentation.
 *
 * <p>These tests assert on real {@link ILoggingEvent}s captured by a
 * {@link CapturedLogs} appender, not on the shape of the source code: level,
 * message, key-value pairs, MDC, argument array and throwable proxy are all
 * inspected, because every one of them is a surface an exporter could carry.
 *
 * <p>The telemetry under test uses {@link OpenTelemetry#noop()}: log emission
 * must not depend on an SDK being installed. Span and metric behaviour is
 * covered by {@link BusinessTelemetryTest}.
 */
class StructuredLogEventsTest {

    /**
     * Strings that must never appear on any surface of an exported event. Each
     * one stands for a category the privacy policy forbids: manuscript content,
     * prompt text, provider error text, credentials, e-mail, and the kind of
     * UUID that identifies a tenant, user, book, scene or operation.
     */
    private static final List<String> CANARIES = List.of(
            "sk-test-canary",
            "Bearer-test-canary",
            "github_pat_test_canary",
            "email@example.com",
            "00000000-0000-0000-0000-000000000123",
            "conteudo privado do manuscrito",
            "prompt secreto",
            "provider exception with secret"
    );

    /** Keys the scene-save event is allowed to carry. Nothing else may appear. */
    private static final List<String> SAVE_EVENT_ALLOWLIST = List.of(
            BusinessTelemetry.EVENT_NAME_KEY,
            "iwrite.operation",
            "iwrite.result",
            BusinessTelemetry.DURATION_MS_KEY,
            "iwrite.scene.source",
            "iwrite.scene.content_size_bucket",
            "iwrite.scene.content_changed",
            "iwrite.error.type"
    );

    private final CapturedLogs logs = new CapturedLogs();
    private final BusinessTelemetry telemetry = new BusinessTelemetry(OpenTelemetry.noop());

    @AfterEach
    void tearDown() {
        logs.close();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        ((LoggerContext) LoggerFactory.getILoggerFactory()).getTurboFilterList().clear();
    }

    @Test
    void successEmitsOneInfoEventWithControlledFields() {
        try (BusinessTelemetry.Operation operation = telemetry.sceneContentSave()) {
            operation.attribute(BusinessTelemetry.SCENE_SOURCE, BusinessTelemetry.SOURCE_MANUAL_SAVE)
                    .attribute(BusinessTelemetry.SCENE_CONTENT_SIZE_BUCKET, BusinessTelemetry.BUCKET_SMALL)
                    .attribute(BusinessTelemetry.SCENE_CONTENT_CHANGED, true);
        }

        ILoggingEvent event = logs.single(BusinessTelemetry.SPAN_SCENE_CONTENT_SAVE);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getLoggerName()).isEqualTo(BusinessTelemetry.EVENT_LOGGER);
        assertThat(event.getMessage()).isEqualTo(BusinessTelemetry.EVENT_MESSAGE);
        assertThat(event.getFormattedMessage()).isEqualTo(BusinessTelemetry.EVENT_MESSAGE);

        Map<String, Object> fields = CapturedLogs.keyValues(event);
        assertThat(fields)
                .containsEntry(BusinessTelemetry.EVENT_NAME_KEY, BusinessTelemetry.SPAN_SCENE_CONTENT_SAVE)
                .containsEntry("iwrite.operation", BusinessTelemetry.OPERATION_SCENE_CONTENT_SAVE)
                .containsEntry("iwrite.result", BusinessTelemetry.RESULT_SUCCESS)
                .containsEntry("iwrite.scene.source", BusinessTelemetry.SOURCE_MANUAL_SAVE)
                .containsEntry("iwrite.scene.content_size_bucket", BusinessTelemetry.BUCKET_SMALL)
                .containsEntry("iwrite.scene.content_changed", true);
        assertThat(fields.get(BusinessTelemetry.DURATION_MS_KEY))
                .isInstanceOf(Long.class)
                .satisfies(duration -> assertThat((Long) duration).isNotNegative());
    }

    /** The message must stay a fixed string: nothing searchable may depend on parsing it. */
    @Test
    void messageIsStableAcrossResultsAndCarriesNoArguments() {
        telemetry.sceneContentSave().close();
        try (BusinessTelemetry.Operation operation = telemetry.sceneContentSave()) {
            operation.result(BusinessTelemetry.RESULT_CONFLICT);
        }

        assertThat(logs.all(BusinessTelemetry.SPAN_SCENE_CONTENT_SAVE))
                .hasSize(2)
                .allSatisfy(event -> {
                    assertThat(event.getMessage()).isEqualTo(BusinessTelemetry.EVENT_MESSAGE);
                    assertThat(event.getArgumentArray()).isNullOrEmpty();
                    assertThat(event.getThrowableProxy()).isNull();
                });
    }

    @Test
    void conflictIsWarnWithoutExceptionMessageOrStackTrace() {
        try (BusinessTelemetry.Operation operation = telemetry.sceneContentSave()) {
            operation.failure(
                    BusinessTelemetry.RESULT_CONFLICT,
                    new IllegalStateException("scene 00000000-0000-0000-0000-000000000123 conteudo privado do manuscrito")
            );
        }

        ILoggingEvent event = logs.single(BusinessTelemetry.SPAN_SCENE_CONTENT_SAVE);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getThrowableProxy()).isNull();
        assertThat(CapturedLogs.keyValues(event))
                .containsEntry("iwrite.result", BusinessTelemetry.RESULT_CONFLICT)
                .containsEntry("iwrite.error.type", "IllegalStateException");
        assertNoCanaries(event);
    }

    /**
     * The event is emitted where the result is decided — at transaction
     * completion — so a rollback that only happens after the business method
     * returned can never be reported as {@code success}.
     */
    @Test
    void rollbackAfterMethodReturnLogsFailureNotSuccess() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            BusinessTelemetry.Operation operation = telemetry.sceneContentSave();
            assertThat(operation.deferEndToTransaction()).isTrue();
            operation.result(BusinessTelemetry.RESULT_SUCCESS);
            operation.detachScope();

            assertThat(logs.all(BusinessTelemetry.SPAN_SCENE_CONTENT_SAVE))
                    .as("nothing may be logged before commit or rollback is known")
                    .isEmpty();

            completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        ILoggingEvent event = logs.single(BusinessTelemetry.SPAN_SCENE_CONTENT_SAVE);
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(CapturedLogs.keyValues(event))
                .containsEntry("iwrite.result", BusinessTelemetry.RESULT_FAILURE);
    }

    @Test
    void noChangeAndIdempotentRetryStayInfoWhenTheTransactionCommits() {
        for (String result : List.of(BusinessTelemetry.RESULT_NO_CHANGE, BusinessTelemetry.RESULT_IDEMPOTENT_RETRY)) {
            TransactionSynchronizationManager.initSynchronization();
            try {
                BusinessTelemetry.Operation operation = telemetry.sceneContentSave();
                assertThat(operation.deferEndToTransaction()).isTrue();
                operation.result(result).attribute(BusinessTelemetry.SCENE_CONTENT_CHANGED, false);
                operation.detachScope();
                completeTransaction(TransactionSynchronization.STATUS_COMMITTED);
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }

        List<ILoggingEvent> events = logs.all(BusinessTelemetry.SPAN_SCENE_CONTENT_SAVE);
        assertThat(events).hasSize(2).allSatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(CapturedLogs.keyValues(event)).containsEntry("iwrite.scene.content_changed", false);
        });
        assertThat(events).map(event -> CapturedLogs.keyValues(event).get("iwrite.result"))
                .containsExactly(BusinessTelemetry.RESULT_NO_CHANGE, BusinessTelemetry.RESULT_IDEMPOTENT_RETRY);
    }

    @Test
    void unexpectedInternalFailureIsErrorAndLeaksOnlyTheExceptionSimpleName() {
        try (BusinessTelemetry.Operation operation = telemetry.sceneContentSave()) {
            operation.failure(
                    BusinessTelemetry.RESULT_FAILURE,
                    new IllegalArgumentException("provider exception with secret sk-test-canary")
            );
        }

        ILoggingEvent event = logs.single(BusinessTelemetry.SPAN_SCENE_CONTENT_SAVE);
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(CapturedLogs.keyValues(event)).containsEntry("iwrite.error.type", "IllegalArgumentException");
        assertNoCanaries(event);
    }

    @Test
    void saveEventCarriesOnlyAllowlistedKeys() {
        try (BusinessTelemetry.Operation operation = telemetry.sceneContentSave()) {
            operation.attribute(BusinessTelemetry.SCENE_SOURCE, BusinessTelemetry.SOURCE_AUTOSAVE)
                    .attribute(BusinessTelemetry.SCENE_CONTENT_SIZE_BUCKET, BusinessTelemetry.BUCKET_LARGE)
                    .attribute(BusinessTelemetry.SCENE_CONTENT_CHANGED, true)
                    .failure(BusinessTelemetry.RESULT_CONFLICT, new IllegalStateException("ignored"));
        }

        assertThat(CapturedLogs.keyValues(logs.single(BusinessTelemetry.SPAN_SCENE_CONTENT_SAVE)))
                .containsOnlyKeys(SAVE_EVENT_ALLOWLIST.toArray(String[]::new));
    }

    /**
     * Values outside a key's closed vocabulary are dropped by the same barrier
     * that protects the span, so free text handed to the attribute API never
     * reaches the log event either.
     */
    @Test
    void rejectedAttributeValuesNeverReachTheEvent() {
        try (BusinessTelemetry.Operation operation = telemetry.sceneContentSave()) {
            operation.attribute(BusinessTelemetry.SCENE_SOURCE, "conteudo privado do manuscrito")
                    .attribute(BusinessTelemetry.SCENE_CONTENT_SIZE_BUCKET, "sk-test-canary")
                    .attribute(BusinessTelemetry.ERROR_TYPE, "email@example.com");
        }

        ILoggingEvent event = logs.single(BusinessTelemetry.SPAN_SCENE_CONTENT_SAVE);
        assertThat(CapturedLogs.keyValues(event))
                .containsOnlyKeys(BusinessTelemetry.EVENT_NAME_KEY, "iwrite.operation", "iwrite.result",
                        BusinessTelemetry.DURATION_MS_KEY);
        assertNoCanaries(event);
    }

    @Test
    void noCanaryReachesAnySurfaceOfAnalysisEvents() {
        try (BusinessTelemetry.Operation operation = telemetry.sceneAnalysis()) {
            operation.attribute(BusinessTelemetry.AI_FOCUS_PRESENT, true)
                    .attribute(BusinessTelemetry.AI_PROVIDER, BusinessTelemetry.PROVIDER_OPENAI)
                    .attribute(BusinessTelemetry.AI_MODEL_FAMILY, BusinessTelemetry.modelFamily("sk-test-canary"))
                    .attribute(BusinessTelemetry.AI_INPUT_SIZE_BUCKET,
                            BusinessTelemetry.modelInputSizeBucket("prompt secreto".length(), false))
                    .failure(BusinessTelemetry.RESULT_PROVIDER_ERROR,
                            new IllegalStateException("provider exception with secret Bearer-test-canary"));
        }

        ILoggingEvent event = logs.single(BusinessTelemetry.SPAN_SCENE_ANALYSIS);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(CapturedLogs.keyValues(event))
                .containsEntry("iwrite.result", BusinessTelemetry.RESULT_PROVIDER_ERROR)
                .containsEntry("iwrite.ai.model_family", BusinessTelemetry.MODEL_FAMILY_OTHER);
        assertNoCanaries(event);
    }

    /** A deferred completion followed by a defensive close must log exactly once. */
    @Test
    void idempotentCloseAfterDeferredEndDoesNotDuplicateTheEvent() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            BusinessTelemetry.Operation operation = telemetry.sceneContentSave();
            assertThat(operation.deferEndToTransaction()).isTrue();
            operation.detachScope();
            completeTransaction(TransactionSynchronization.STATUS_COMMITTED);
            operation.close();
            operation.close();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertThat(logs.all(BusinessTelemetry.SPAN_SCENE_CONTENT_SAVE)).hasSize(1);
    }

    /**
     * A logging pipeline that throws must not fail the business operation. A
     * Logback {@link TurboFilter} is used because, unlike an appender failure,
     * it propagates out of the logging call and therefore reaches the guard in
     * the telemetry code rather than Logback's own status handler.
     */
    @Test
    void loggingInfrastructureFailureDoesNotBreakTheBusinessOperation() {
        ((LoggerContext) LoggerFactory.getILoggerFactory()).addTurboFilter(new TurboFilter() {
            @Override
            public FilterReply decide(Marker marker, ch.qos.logback.classic.Logger logger, Level level,
                                      String format, Object[] params, Throwable throwable) {
                throw new IllegalStateException("logging pipeline is down");
            }
        });

        assertThatCode(() -> {
            try (BusinessTelemetry.Operation operation = telemetry.sceneContentSave()) {
                operation.attribute(BusinessTelemetry.SCENE_SOURCE, BusinessTelemetry.SOURCE_MANUAL_SAVE);
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void mcpInvocationEventsCarryOnlyControlledValues() {
        McpInvocationSupport mcp = new McpInvocationSupport(mock(AuditLogService.class));
        UUID clientResourceId = UUID.fromString("00000000-0000-0000-0000-000000000123");

        mcp.invoke("iwrite_get_scene", AuditAction.SCENE_UPDATED, AuditResourceType.SCENE, clientResourceId,
                () -> "conteudo privado do manuscrito");
        assertThatThrownBy(() -> mcp.invoke("iwrite_analyze_scene", AuditAction.SCENE_UPDATED, AuditResourceType.SCENE,
                clientResourceId, () -> {
                    throw new IllegalStateException("provider exception with secret sk-test-canary");
                }))
                .isInstanceOf(McpToolException.class);

        List<ILoggingEvent> events = logs.all("iwrite.mcp.invocation");
        assertThat(events).hasSize(2);
        assertThat(events.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(CapturedLogs.keyValues(events.get(0)))
                .containsOnlyKeys(BusinessTelemetry.EVENT_NAME_KEY, "iwrite.mcp.tool", "iwrite.mcp.resource_type",
                        "iwrite.result", BusinessTelemetry.DURATION_MS_KEY)
                .containsEntry("iwrite.mcp.tool", "iwrite_get_scene")
                .containsEntry("iwrite.result", BusinessTelemetry.RESULT_SUCCESS);

        // IllegalStateException não é reconhecida -> categoria interna -> ERROR.
        assertThat(events.get(1).getLevel()).isEqualTo(Level.ERROR);
        assertThat(events.get(1).getThrowableProxy()).isNull();
        assertThat(CapturedLogs.keyValues(events.get(1)))
                .containsOnlyKeys(BusinessTelemetry.EVENT_NAME_KEY, "iwrite.mcp.tool", "iwrite.mcp.resource_type",
                        "iwrite.result", "iwrite.error.category", BusinessTelemetry.DURATION_MS_KEY)
                .containsEntry("iwrite.result", BusinessTelemetry.RESULT_FAILURE);
        events.forEach(StructuredLogEventsTest::assertNoCanaries);
    }

    /**
     * A tool that is merely missing a resource and a tool that hit a server
     * defect must not share a severity, or ERROR-based alerting would never
     * observe an internal MCP failure.
     */
    @Test
    void mcpInternalFailureIsErrorWhileExpectedCategoriesStayWarn() {
        McpInvocationSupport mcp = new McpInvocationSupport(mock(AuditLogService.class));
        UUID resourceId = UUID.fromString("00000000-0000-0000-0000-000000000123");

        assertThatThrownBy(() -> mcp.invoke("iwrite_get_scene", AuditAction.SCENE_UPDATED,
                AuditResourceType.SCENE, resourceId, () -> {
                    throw new IllegalStateException("unrecognized sk-test-canary");
                })).isInstanceOf(McpToolException.class);
        assertThatThrownBy(() -> mcp.invoke("iwrite_get_scene", AuditAction.SCENE_UPDATED,
                AuditResourceType.SCENE, resourceId, () -> {
                    throw new ResourceNotFoundException("Scene not found");
                })).isInstanceOf(McpToolException.class);

        List<ILoggingEvent> events = logs.all("iwrite.mcp.invocation");
        assertThat(events).hasSize(2);
        assertThat(events.get(0).getLevel()).isEqualTo(Level.ERROR);
        assertThat(CapturedLogs.keyValues(events.get(0)))
                .containsEntry("iwrite.error.category", McpToolException.CATEGORY_INTERNAL);
        assertThat(events.get(1).getLevel()).isEqualTo(Level.WARN);
        assertThat(CapturedLogs.keyValues(events.get(1)))
                .containsEntry("iwrite.error.category", McpToolException.CATEGORY_NOT_FOUND);
        events.forEach(StructuredLogEventsTest::assertNoCanaries);
    }

    /**
     * {@code McpToolException.from} collapses every {@code LlmExecutionException}
     * into the client-facing {@code unavailable}, which is right for the client
     * but would hide a broken deployment behind the same severity as a provider
     * outage. Severity must come from the original category; the exported
     * category stays sanitized for every one of {@link LlmErrorCategory}'s
     * values, not just the two most obvious ones.
     */
    @ParameterizedTest
    @MethodSource("llmCategoriesAndExpectedLevel")
    void mcpDerivesSeverityFromTheOriginalLlmCategoryNotTheSanitizedOne(
            LlmExecutionStatus status, LlmErrorCategory category, Level expectedLevel) {
        McpInvocationSupport mcp = new McpInvocationSupport(mock(AuditLogService.class));
        UUID resourceId = UUID.fromString("00000000-0000-0000-0000-000000000123");

        assertThatThrownBy(() -> mcp.invoke("iwrite_analyze_scene", AuditAction.SCENE_UPDATED,
                AuditResourceType.SCENE, resourceId, () -> {
                    throw new LlmExecutionException(status, category, UUID.randomUUID(),
                            new IllegalStateException("provider exception with secret sk-test-canary"));
                })).isInstanceOf(McpToolException.class)
                .hasMessageContaining(McpToolException.CATEGORY_UNAVAILABLE)
                .satisfies(exception -> assertThat(((McpToolException) exception).category())
                        .as("client never sees the internal LLM category")
                        .isEqualTo(McpToolException.CATEGORY_UNAVAILABLE));

        ILoggingEvent event = logs.single("iwrite.mcp.invocation");
        assertThat(event.getLevel()).as("category %s", category).isEqualTo(expectedLevel);
        assertThat(event.getThrowableProxy()).isNull();
        assertThat(CapturedLogs.keyValues(event)).containsEntry("iwrite.error.category", McpToolException.CATEGORY_UNAVAILABLE);
        assertNoCanaries(event);
    }

    private static Stream<Arguments> llmCategoriesAndExpectedLevel() {
        return Stream.of(
                Arguments.of(LlmExecutionStatus.TIMED_OUT, LlmErrorCategory.PROVIDER_TIMEOUT, Level.WARN),
                Arguments.of(LlmExecutionStatus.UNAVAILABLE, LlmErrorCategory.PROVIDER_UNAVAILABLE, Level.WARN),
                Arguments.of(LlmExecutionStatus.FAILED, LlmErrorCategory.PROVIDER_REQUEST_REJECTED, Level.WARN),
                Arguments.of(LlmExecutionStatus.INVALID_RESPONSE, LlmErrorCategory.INVALID_STRUCTURED_RESPONSE, Level.WARN),
                Arguments.of(LlmExecutionStatus.DISABLED, LlmErrorCategory.FEATURE_DISABLED, Level.WARN),
                Arguments.of(LlmExecutionStatus.FAILED, LlmErrorCategory.CONFIGURATION_ERROR, Level.ERROR),
                Arguments.of(LlmExecutionStatus.FAILED, LlmErrorCategory.AUDIT_PERSISTENCE_FAILURE, Level.ERROR),
                Arguments.of(LlmExecutionStatus.FAILED, LlmErrorCategory.INTERNAL_EXECUTION_ERROR, Level.ERROR)
        );
    }

    /**
     * The disabled assistant is the default deployment, so an ordinary 503 must
     * not raise an internal-failure event.
     */
    @Test
    void disabledAiFeatureIsWarnNotError() {
        try (BusinessTelemetry.Operation operation = telemetry.sceneAnalysis()) {
            operation.attribute(BusinessTelemetry.AI_PROVIDER, BusinessTelemetry.PROVIDER_DISABLED)
                    .failure(BusinessTelemetry.RESULT_PROVIDER_ERROR,
                            new IllegalStateException("This AI feature is disabled."));
        }

        ILoggingEvent event = logs.single(BusinessTelemetry.SPAN_SCENE_ANALYSIS);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(CapturedLogs.keyValues(event))
                .containsEntry("iwrite.result", BusinessTelemetry.RESULT_PROVIDER_ERROR)
                .containsEntry("iwrite.ai.provider", BusinessTelemetry.PROVIDER_DISABLED);
    }

    @Test
    void unknownProviderCollapsesToTheClosedVocabulary() {
        assertThat(BusinessTelemetry.providerName("sk-test-canary")).isEqualTo(BusinessTelemetry.PROVIDER_UNKNOWN);
        assertThat(BusinessTelemetry.providerName(null)).isEqualTo(BusinessTelemetry.PROVIDER_UNKNOWN);
        assertThat(BusinessTelemetry.providerName(BusinessTelemetry.PROVIDER_OPENAI))
                .isEqualTo(BusinessTelemetry.PROVIDER_OPENAI);
    }

    private static void assertNoCanaries(ILoggingEvent event) {
        String surfaces = CapturedLogs.allSurfaces(event);
        assertThat(surfaces).as("every surface of event %s", event.getMessage())
                .doesNotContainIgnoringCase(CANARIES.toArray(CharSequence[]::new));
    }

    private static void completeTransaction(int status) {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(status);
        }
    }
}
