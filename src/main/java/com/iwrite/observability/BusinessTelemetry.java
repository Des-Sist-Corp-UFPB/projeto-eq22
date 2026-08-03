package com.iwrite.observability;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Single place where IWrite business flows emit manual spans and metrics.
 *
 * <p>The SDK is never created here: the {@code OpenTelemetry} instance comes
 * from {@link GlobalOpenTelemetry}, which is the OpenTelemetry Java Agent when
 * it is attached and the no-op implementation when it is not. Nothing in this
 * class changes business behaviour — every telemetry failure is swallowed, and
 * a rejected attribute is dropped rather than reported.
 *
 * <p>Privacy contract: only the keys in {@link #ALLOWED_KEYS} can be written.
 * Every key with a fixed set of legitimate values — operation, result, scene
 * source, size bucket, AI provider, AI model family — is validated against a
 * small closed vocabulary in {@link #CLOSED_VOCABULARIES}: a value outside
 * that vocabulary is dropped, never forwarded as-is. This is what stops a
 * misconfigured value (e.g. a credential placed in {@code OPENAI_MODEL}) from
 * being exported; {@link #modelFamily(String)} in particular never lets the
 * raw model string reach a span. The one remaining open-ended string
 * attribute, {@code iwrite.error.type}, only ever receives an exception's
 * simple class name, so it keeps the shape filter in {@link #isControlled}
 * instead, with a credential-prefix rejection as an extra layer.
 *
 * <p>Duration unit: milliseconds, recorded from {@link System#nanoTime()}.
 */
@Component
public class BusinessTelemetry {

    public static final String INSTRUMENTATION_SCOPE = "com.iwrite.observability";

    public static final String SPAN_SCENE_CONTENT_SAVE = "iwrite.scene.content.save";
    public static final String SPAN_SCENE_ANALYSIS = "iwrite.scene.analysis";

    public static final String OPERATION_SCENE_CONTENT_SAVE = "scene_content_save";
    public static final String OPERATION_SCENE_ANALYSIS = "scene_analysis";

    public static final String METRIC_OPERATION_COUNT = "iwrite.business.operation.count";
    public static final String METRIC_OPERATION_DURATION = "iwrite.business.operation.duration";
    public static final String DURATION_UNIT = "ms";

    public static final String RESULT_SUCCESS = "success";
    public static final String RESULT_FAILURE = "failure";
    public static final String RESULT_CONFLICT = "conflict";
    public static final String RESULT_NO_CHANGE = "no_change";
    public static final String RESULT_IDEMPOTENT_RETRY = "idempotent_retry";
    public static final String RESULT_VALIDATION_ERROR = "validation_error";
    public static final String RESULT_PROVIDER_ERROR = "provider_error";
    public static final String RESULT_INVALID_RESPONSE = "invalid_response";

    public static final String BUCKET_EMPTY = "empty";
    public static final String BUCKET_SMALL = "small";
    public static final String BUCKET_MEDIUM = "medium";
    public static final String BUCKET_LARGE = "large";
    public static final String BUCKET_TRUNCATED = "truncated";

    public static final String SOURCE_MANUAL_SAVE = "manual_save";
    public static final String SOURCE_AUTOSAVE = "autosave";
    public static final String SOURCE_RESTORE = "restore";
    public static final String SOURCE_OTHER = "other";

    public static final String PROVIDER_OPENAI = "openai";
    public static final String PROVIDER_DISABLED = "disabled";

    /**
     * Small, explicit set of model families. {@link #modelFamily(String)} is
     * the only place allowed to turn a configured model identifier (which may
     * be anything, including a misplaced credential) into one of these.
     */
    public static final String MODEL_FAMILY_GPT_4O = "gpt-4o";
    public static final String MODEL_FAMILY_GPT_4_1 = "gpt-4.1";
    public static final String MODEL_FAMILY_GPT_5 = "gpt-5";
    public static final String MODEL_FAMILY_OTHER = "other";
    public static final String MODEL_FAMILY_UNKNOWN = "unknown";

    public static final AttributeKey<String> OPERATION = AttributeKey.stringKey("iwrite.operation");
    public static final AttributeKey<String> RESULT = AttributeKey.stringKey("iwrite.result");
    public static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("iwrite.error.type");
    public static final AttributeKey<String> SCENE_SOURCE = AttributeKey.stringKey("iwrite.scene.source");
    public static final AttributeKey<String> SCENE_CONTENT_SIZE_BUCKET =
            AttributeKey.stringKey("iwrite.scene.content_size_bucket");
    public static final AttributeKey<Boolean> SCENE_CONTENT_CHANGED =
            AttributeKey.booleanKey("iwrite.scene.content_changed");
    public static final AttributeKey<Boolean> AI_FOCUS_PRESENT = AttributeKey.booleanKey("iwrite.ai.focus_present");
    public static final AttributeKey<String> AI_INPUT_SIZE_BUCKET =
            AttributeKey.stringKey("iwrite.ai.input_size_bucket");
    public static final AttributeKey<Boolean> AI_FALLBACK_USED = AttributeKey.booleanKey("iwrite.ai.fallback_used");
    public static final AttributeKey<String> AI_PROVIDER = AttributeKey.stringKey("iwrite.ai.provider");
    public static final AttributeKey<String> AI_MODEL_FAMILY = AttributeKey.stringKey("iwrite.ai.model_family");

    /** Metric labels stay at these two on purpose; nothing else is ever attached. */
    private static final AttributeKey<String> METRIC_OPERATION = AttributeKey.stringKey("operation");
    private static final AttributeKey<String> METRIC_RESULT = AttributeKey.stringKey("result");

    private static final Set<AttributeKey<?>> ALLOWED_KEYS = Set.of(
            OPERATION,
            RESULT,
            ERROR_TYPE,
            SCENE_SOURCE,
            SCENE_CONTENT_SIZE_BUCKET,
            SCENE_CONTENT_CHANGED,
            AI_FOCUS_PRESENT,
            AI_INPUT_SIZE_BUCKET,
            AI_FALLBACK_USED,
            AI_PROVIDER,
            AI_MODEL_FAMILY
    );

    private static final Map<String, Set<String>> ALLOWED_RESULTS = Map.of(
            OPERATION_SCENE_CONTENT_SAVE,
            Set.of(RESULT_SUCCESS, RESULT_CONFLICT, RESULT_NO_CHANGE, RESULT_IDEMPOTENT_RETRY, RESULT_FAILURE),
            OPERATION_SCENE_ANALYSIS,
            Set.of(RESULT_SUCCESS, RESULT_VALIDATION_ERROR, RESULT_PROVIDER_ERROR, RESULT_INVALID_RESPONSE, RESULT_FAILURE)
    );

    private static final Set<String> SIZE_BUCKETS =
            Set.of(BUCKET_EMPTY, BUCKET_SMALL, BUCKET_MEDIUM, BUCKET_LARGE, BUCKET_TRUNCATED);

    /**
     * Primary barrier for every string attribute with a fixed set of
     * legitimate values: a value outside the vocabulary for its key is
     * dropped, regardless of shape. {@link #ERROR_TYPE} is deliberately
     * absent — it is the one attribute without a closed vocabulary, and
     * falls back to {@link #isControlled} instead.
     */
    private static final Map<AttributeKey<String>, Set<String>> CLOSED_VOCABULARIES = Map.of(
            SCENE_SOURCE, Set.of(SOURCE_MANUAL_SAVE, SOURCE_AUTOSAVE, SOURCE_RESTORE, SOURCE_OTHER),
            SCENE_CONTENT_SIZE_BUCKET, SIZE_BUCKETS,
            AI_INPUT_SIZE_BUCKET, SIZE_BUCKETS,
            AI_PROVIDER, Set.of(PROVIDER_OPENAI, PROVIDER_DISABLED),
            AI_MODEL_FAMILY, Set.of(
                    MODEL_FAMILY_GPT_4O, MODEL_FAMILY_GPT_4_1, MODEL_FAMILY_GPT_5,
                    MODEL_FAMILY_OTHER, MODEL_FAMILY_UNKNOWN)
    );

    /**
     * Fallback shape filter for {@link #ERROR_TYPE}, the one string attribute
     * without a closed vocabulary: short identifier shape, no whitespace, no
     * accents, no {@code @}, at most 64 characters. Free text, e-mails and
     * manuscript excerpts cannot match it.
     */
    private static final Pattern CONTROLLED_VALUE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,63}");

    /** Second filter: a value shaped like a UUID is rejected even if short. */
    private static final Pattern UUID_LIKE =
            Pattern.compile("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /**
     * Third filter, additional defense-in-depth and not the primary barrier:
     * rejects common credential/token prefixes outright, even though they
     * would otherwise match {@link #CONTROLLED_VALUE}.
     */
    private static final Pattern CREDENTIAL_LIKE = Pattern.compile(
            "(?i)^(sk|pk|rk)[-_]|^gh[opsu]_|^github_pat_|^bearer[-_ ]|^eyj");

    private static final int SMALL_MAX_CHARS = 2_000;
    private static final int MEDIUM_MAX_CHARS = 20_000;

    private final Tracer tracer;
    private final LongCounter operationCount;
    private final DoubleHistogram operationDuration;

    /** Used by Spring: the agent's instance when attached, no-op otherwise. */
    public BusinessTelemetry() {
        this(GlobalOpenTelemetry.get());
    }

    public BusinessTelemetry(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_SCOPE);
        Meter meter = openTelemetry.getMeter(INSTRUMENTATION_SCOPE);
        this.operationCount = meter.counterBuilder(METRIC_OPERATION_COUNT)
                .setDescription("Business operations finished, by operation and result.")
                .setUnit("{operation}")
                .build();
        this.operationDuration = meter.histogramBuilder(METRIC_OPERATION_DURATION)
                .setDescription("Wall-clock duration of a business operation.")
                .setUnit(DURATION_UNIT)
                .build();
    }

    public Operation sceneContentSave() {
        return new Operation(SPAN_SCENE_CONTENT_SAVE, OPERATION_SCENE_CONTENT_SAVE);
    }

    public Operation sceneAnalysis() {
        return new Operation(SPAN_SCENE_ANALYSIS, OPERATION_SCENE_ANALYSIS);
    }

    /** Size band of saved scene content. Never the exact length. */
    public static String contentSizeBucket(String content) {
        return sizeBucket(content == null ? 0 : content.length());
    }

    /** Size band of the text handed to the model; truncation wins over size. */
    public static String modelInputSizeBucket(int chars, boolean truncated) {
        return truncated ? BUCKET_TRUNCATED : sizeBucket(chars);
    }

    private static String sizeBucket(int chars) {
        if (chars <= 0) {
            return BUCKET_EMPTY;
        }
        if (chars < SMALL_MAX_CHARS) {
            return BUCKET_SMALL;
        }
        if (chars < MEDIUM_MAX_CHARS) {
            return BUCKET_MEDIUM;
        }
        return BUCKET_LARGE;
    }

    private static boolean isControlled(String value) {
        return value != null
                && CONTROLLED_VALUE.matcher(value).matches()
                && !UUID_LIKE.matcher(value).matches()
                && !CREDENTIAL_LIKE.matcher(value).find();
    }

    /**
     * Normalizes a configured model identifier (e.g. {@code OPENAI_MODEL})
     * into one of {@link #MODEL_FAMILY_GPT_4O}, {@link #MODEL_FAMILY_GPT_4_1},
     * {@link #MODEL_FAMILY_GPT_5}, {@link #MODEL_FAMILY_OTHER} or
     * {@link #MODEL_FAMILY_UNKNOWN}. {@code rawModel} is read only for this
     * prefix comparison and is never attached to a span or metric.
     */
    public static String modelFamily(String rawModel) {
        if (rawModel == null || rawModel.isBlank()) {
            return MODEL_FAMILY_UNKNOWN;
        }
        String normalized = rawModel.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("gpt-4o")) {
            return MODEL_FAMILY_GPT_4O;
        }
        if (normalized.startsWith("gpt-4.1")) {
            return MODEL_FAMILY_GPT_4_1;
        }
        if (normalized.startsWith("gpt-5")) {
            return MODEL_FAMILY_GPT_5;
        }
        return MODEL_FAMILY_OTHER;
    }

    private static boolean isAllowedValue(AttributeKey<String> key, String value) {
        if (value == null || !ALLOWED_KEYS.contains(key)) {
            return false;
        }
        Set<String> vocabulary = CLOSED_VOCABULARIES.get(key);
        return vocabulary != null ? vocabulary.contains(value) : isControlled(value);
    }

    /**
     * One in-flight business operation: a span, a stopwatch and a result. Not
     * thread-safe; it lives inside a single request thread.
     */
    public final class Operation implements AutoCloseable {

        private final String operation;
        private final long startedNanos;
        private final Span span;
        private final Scope scope;
        private String result;
        private boolean ended;
        private boolean failed;

        private Operation(String spanName, String operation) {
            this.operation = operation;
            this.startedNanos = System.nanoTime();
            Span startedSpan;
            Scope openedScope;
            try {
                startedSpan = tracer.spanBuilder(spanName).startSpan();
                startedSpan.setAttribute(OPERATION, operation);
                openedScope = startedSpan.makeCurrent();
            } catch (RuntimeException telemetryFailure) {
                startedSpan = Span.getInvalid();
                openedScope = Scope.noop();
            }
            this.span = startedSpan;
            this.scope = openedScope;
        }

        public Operation attribute(AttributeKey<String> key, String value) {
            if (!ended && isAllowedValue(key, value)) {
                setSafely(key, value);
            }
            return this;
        }

        public Operation attribute(AttributeKey<Boolean> key, boolean value) {
            if (!ended && ALLOWED_KEYS.contains(key)) {
                setSafely(key, value);
            }
            return this;
        }

        /** Classifies the outcome. Values outside the operation's set become {@code failure}. */
        public Operation result(String result) {
            if (!ended) {
                this.result = ALLOWED_RESULTS.getOrDefault(operation, Set.of()).contains(result)
                        ? result
                        : RESULT_FAILURE;
            }
            return this;
        }

        /**
         * Marks the span as failed. Only the simple class name of the exception
         * is recorded — never its message, and never {@code recordException},
         * which would let the agent capture an unsanitized message.
         */
        public Operation failure(String result, Throwable failure) {
            result(result);
            if (!ended) {
                failed = true;
                try {
                    span.setStatus(StatusCode.ERROR);
                } catch (RuntimeException telemetryFailure) {
                    // telemetry must never break the business operation
                }
                if (failure != null) {
                    attribute(ERROR_TYPE, failure.getClass().getSimpleName());
                }
            }
            return this;
        }

        /**
         * Defers {@link #close()} until the current transaction finishes, so
         * flush/commit time counts toward the span and a failure that only
         * surfaces at commit is not reported as {@code success}. Returns
         * {@code false} when no transaction synchronization is active or
         * registration fails; the caller must then call {@link #close()}
         * itself (typically in a {@code finally} block).
         *
         * <p>On a non-committed completion (rollback or unknown), a result
         * not already classified via {@link #failure(String, Throwable)} is
         * demoted to {@link #RESULT_FAILURE} and the span is marked
         * {@link StatusCode#ERROR}. No exception type or message is
         * invented: the callback has no safe access to the cause.
         */
        public boolean deferCloseToTransaction() {
            try {
                if (ended || !TransactionSynchronizationManager.isSynchronizationActive()) {
                    return false;
                }
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status != TransactionSynchronization.STATUS_COMMITTED) {
                            demoteToFailure();
                        }
                        close();
                    }
                });
                return true;
            } catch (RuntimeException telemetryFailure) {
                // telemetry must never break the business operation
                return false;
            }
        }

        private void demoteToFailure() {
            try {
                if (!failed) {
                    result = RESULT_FAILURE;
                }
                span.setStatus(StatusCode.ERROR);
            } catch (RuntimeException telemetryFailure) {
                // telemetry must never break the business operation
            }
        }

        /** Ends the span and records both metrics. Safe to call twice. */
        @Override
        public void close() {
            if (ended) {
                return;
            }
            ended = true;
            double elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000.0;
            String finalResult = result == null ? RESULT_SUCCESS : result;
            try {
                span.setAttribute(RESULT, finalResult);
                Attributes labels = Attributes.of(METRIC_OPERATION, operation, METRIC_RESULT, finalResult);
                operationCount.add(1, labels);
                operationDuration.record(elapsedMs, labels);
            } catch (RuntimeException telemetryFailure) {
                // exporting telemetry must never fail the business operation
            }
            closeSafely(scope);
            endSafely(span);
        }

        private <T> void setSafely(AttributeKey<T> key, T value) {
            try {
                span.setAttribute(key, value);
            } catch (RuntimeException telemetryFailure) {
                // dropping one attribute is always preferable to failing the request
            }
        }

        private void closeSafely(Scope openScope) {
            try {
                openScope.close();
            } catch (RuntimeException telemetryFailure) {
                // ignored on purpose
            }
        }

        private void endSafely(Span openSpan) {
            try {
                openSpan.end();
            } catch (RuntimeException telemetryFailure) {
                // ignored on purpose
            }
        }
    }
}
