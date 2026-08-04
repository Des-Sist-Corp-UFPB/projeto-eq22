package com.iwrite.llm.gateway;

import com.iwrite.llm.audit.LlmErrorCategory;
import com.iwrite.llm.audit.LlmExecutionAuditRecorder;
import com.iwrite.llm.audit.LlmExecutionCompletion;
import com.iwrite.llm.audit.LlmExecutionStart;
import com.iwrite.llm.audit.LlmExecutionStatus;
import com.iwrite.llm.LlmTokenUsage;
import com.iwrite.llm.cost.LlmCostEstimate;
import com.iwrite.llm.cost.LlmCostEstimator;
import com.iwrite.observability.BusinessTelemetry;
import com.iwrite.user.context.CurrentUserProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Single instrumented entry point for every product LLM execution.
 *
 * <p>Responsibilities are fixed here so features cannot diverge: audit-start
 * persistence, provider invocation, latency measurement, error classification,
 * token usage collection, optional cost estimation, terminal audit persistence,
 * and sanitized logging with one audit/execution identifier per logical
 * execution. That identifier ({@code llmExecutionId}) is an audit-table key and
 * is never the OpenTelemetry distributed {@code trace_id}, which the Java Agent
 * injects independently; see docs/otel-correlated-logs.md.
 *
 * <p>Transaction contract: this method must be called without an active
 * database transaction, so no connection is held open during the external
 * provider call. Audit writes run in their own short transactions.
 *
 * <p>Failure contract: if the start audit cannot be persisted the execution is
 * aborted before the provider is invoked (fail-closed auditing). If a terminal
 * audit write fails after the provider call, the product outcome is preserved
 * and the persistence failure is logged with the audit/execution identifier.
 */
@Component
public class LlmExecutionGateway {

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmExecutionGateway.class);

    /**
     * MDC key for the audit UUID of one execution. Named for what it is — an
     * execution/audit identifier — because it is <em>not</em> the OpenTelemetry
     * distributed {@code trace_id}: it correlates log lines with a row in the
     * LLM audit table, nothing more. It stays out of the OTLP attributes
     * because MDC capture is deliberately not enabled for the agent.
     */
    private static final String EXECUTION_MDC_KEY = "llmExecutionId";

    private static final String EVENT_NAME = "iwrite.llm.execution";
    private static final String EVENT_MESSAGE = "LLM execution completed";

    /**
     * Categories that mean the deployment or this service is broken, as opposed
     * to an expected runtime condition (provider timeout, disabled feature,
     * malformed model output). Only these reach ERROR.
     */
    private static final Set<LlmErrorCategory> INTERNAL_FAILURE_CATEGORIES = Set.of(
            LlmErrorCategory.INTERNAL_EXECUTION_ERROR,
            LlmErrorCategory.AUDIT_PERSISTENCE_FAILURE,
            LlmErrorCategory.CONFIGURATION_ERROR
    );

    private final LlmExecutionAuditRecorder auditRecorder;
    private final LlmErrorClassifier errorClassifier;
    private final LlmCostEstimator costEstimator;
    private final CurrentUserProvider currentUserProvider;
    private final Clock clock;

    public LlmExecutionGateway(
            LlmExecutionAuditRecorder auditRecorder,
            LlmErrorClassifier errorClassifier,
            LlmCostEstimator costEstimator,
            CurrentUserProvider currentUserProvider,
            Clock clock
    ) {
        this.auditRecorder = auditRecorder;
        this.errorClassifier = errorClassifier;
        this.costEstimator = costEstimator;
        this.currentUserProvider = currentUserProvider;
        this.clock = clock;
    }

    public <T> T execute(LlmExecutionSpec spec, LlmProviderCall<T> providerCall) {
        requireNoActiveTransaction();

        UUID traceId = UUID.randomUUID();
        OffsetDateTime startedAt = OffsetDateTime.now(clock);
        long startedNanos = System.nanoTime();
        UUID auditId = recordStart(spec, traceId, startedAt);

        try (MDC.MDCCloseable ignored = MDC.putCloseable(EXECUTION_MDC_KEY, traceId.toString())) {
            LlmCallResult<T> result;
            try {
                result = invokeProvider(spec, providerCall, traceId);
            } catch (LlmExecutionException failure) {
                LlmExecutionCompletion completion = LlmExecutionCompletion.failure(
                        failure.getStatus(),
                        failure.getErrorCategory(),
                        spec.model(),
                        OffsetDateTime.now(clock),
                        elapsedMs(startedNanos)
                );
                finalizeAudit(auditId, traceId, completion);
                logOutcome(spec, completion);
                throw failure;
            }

            String effectiveModel = effectiveModel(result.model(), spec.model(), traceId);
            LlmExecutionCompletion completion = LlmExecutionCompletion.success(
                    effectiveModel,
                    OffsetDateTime.now(clock),
                    elapsedMs(startedNanos),
                    result.tokenUsage(),
                    estimateCost(spec.provider(), effectiveModel, result, traceId),
                    result.fallbackUsed()
            );
            finalizeAudit(auditId, traceId, completion);
            logOutcome(spec, completion);
            return result.value();
        }
    }

    private <T> LlmCallResult<T> invokeProvider(
            LlmExecutionSpec spec,
            LlmProviderCall<T> providerCall,
            UUID traceId
    ) {
        LlmCallResult<T> result;
        try {
            result = providerCall.call(new LlmCallContext(traceId));
        } catch (LlmExecutionException alreadyClassified) {
            throw alreadyClassified;
        } catch (Exception failure) {
            LlmErrorClassification classification = errorClassifier.classify(failure);
            throw new LlmExecutionException(classification.status(), classification.category(), traceId, failure);
        }
        if (result == null || result.value() == null) {
            throw new LlmExecutionException(
                    LlmExecutionStatus.INVALID_RESPONSE,
                    LlmErrorCategory.INVALID_STRUCTURED_RESPONSE,
                    traceId,
                    null
            );
        }
        return result;
    }

    private UUID recordStart(LlmExecutionSpec spec, UUID traceId, OffsetDateTime startedAt) {
        try {
            return auditRecorder.recordStart(new LlmExecutionStart(
                    currentUserProvider.tenantId(),
                    currentUserProvider.userId(),
                    spec.feature(),
                    spec.provider(),
                    spec.model(),
                    spec.promptVersion(),
                    traceId,
                    spec.resourceType(),
                    spec.resourceId(),
                    startedAt
            ));
        } catch (RuntimeException persistenceFailure) {
            LOGGER.error(
                    "LLM execution audit start failed feature={} provider={} llmExecutionId={} failureType={}",
                    spec.feature(),
                    spec.provider(),
                    traceId,
                    persistenceFailure.getClass().getSimpleName()
            );
            throw new LlmExecutionException(
                    LlmExecutionStatus.FAILED,
                    LlmErrorCategory.AUDIT_PERSISTENCE_FAILURE,
                    traceId,
                    persistenceFailure
            );
        }
    }

    private void finalizeAudit(UUID auditId, UUID traceId, LlmExecutionCompletion completion) {
        try {
            boolean applied = auditRecorder.complete(auditId, completion);
            if (!applied) {
                LOGGER.warn(
                        "LLM execution audit already finalized; keeping first terminal state llmExecutionId={} auditId={}",
                        traceId,
                        auditId
                );
            }
        } catch (RuntimeException persistenceFailure) {
            LOGGER.error(
                    "LLM execution audit completion failed llmExecutionId={} auditId={} status={} failureType={}",
                    traceId,
                    auditId,
                    completion.status(),
                    persistenceFailure.getClass().getSimpleName()
            );
        }
    }

    private <T> LlmCostEstimate estimateCost(
            String provider,
            String effectiveModel,
            LlmCallResult<T> result,
            UUID traceId
    ) {
        try {
            return costEstimator.estimate(provider, effectiveModel, result.tokenUsage()).orElse(null);
        } catch (RuntimeException estimationFailure) {
            LOGGER.warn(
                    "LLM cost estimation failed; persisting execution without cost llmExecutionId={} failureType={}",
                    traceId,
                    estimationFailure.getClass().getSimpleName()
            );
            return null;
        }
    }

    private String effectiveModel(String reportedModel, String specModel, UUID traceId) {
        if (reportedModel == null) {
            return specModel;
        }
        if (LlmExecutionSpec.isValidModel(reportedModel)) {
            return reportedModel;
        }
        LOGGER.warn("Ignoring invalid provider-reported model metadata llmExecutionId={}", traceId);
        return specModel;
    }

    private void requireNoActiveTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "LlmExecutionGateway.execute must not run inside a database transaction; "
                            + "load data first, then call the gateway outside the transaction."
            );
        }
    }

    private long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    /**
     * One structured event per execution, in SLF4J 2 key-value pairs so the
     * Java Agent exports them as OTLP attributes.
     *
     * <p>Every value is drawn from a controlled vocabulary: {@code feature},
     * {@code status} and {@code errorCategory} are enums, {@code promptVersion}
     * is the {@code name:vN} identifier validated by {@link LlmExecutionSpec},
     * and the provider passes through {@link BusinessTelemetry#providerName}.
     * The configured model is never logged raw — it is an arbitrary string that
     * can hold a misplaced credential, so only its
     * {@link BusinessTelemetry#modelFamily} reaches the event. Prompt, response,
     * provider message and stack trace never appear at all.
     *
     * <p>The audit UUID is deliberately absent: it identifies a row in the LLM
     * audit table, not a distributed trace, and it is already carried in the
     * {@code llmExecutionId} MDC entry for local correlation. Distributed
     * correlation comes from the agent's {@code trace_id}/{@code span_id}.
     */
    private void logOutcome(LlmExecutionSpec spec, LlmExecutionCompletion completion) {
        LoggingEventBuilder event = levelFor(completion);
        event.addKeyValue(BusinessTelemetry.EVENT_NAME_KEY, EVENT_NAME)
                .addKeyValue("iwrite.llm.feature", spec.feature().name())
                .addKeyValue("iwrite.ai.provider", BusinessTelemetry.providerName(spec.provider()))
                .addKeyValue("iwrite.ai.model_family", BusinessTelemetry.modelFamily(completion.model()))
                .addKeyValue("iwrite.llm.prompt_version", spec.promptVersion())
                .addKeyValue("iwrite.llm.status", completion.status().name())
                .addKeyValue("iwrite.error.category",
                        completion.errorCategory() == null ? null : completion.errorCategory().name())
                .addKeyValue(BusinessTelemetry.DURATION_MS_KEY, completion.latencyMs())
                .addKeyValue("iwrite.llm.input_tokens", tokens(completion, LlmTokenUsage::inputTokens))
                .addKeyValue("iwrite.llm.output_tokens", tokens(completion, LlmTokenUsage::outputTokens))
                .addKeyValue("iwrite.llm.total_tokens", tokens(completion, LlmTokenUsage::totalTokens))
                .addKeyValue("iwrite.ai.fallback_used", completion.fallbackUsed())
                .log(EVENT_MESSAGE);
    }

    /**
     * Same level policy as the business events: INFO for success, WARN for an
     * expected and classified outcome, ERROR only for an unexpected internal
     * failure. A provider timeout and a broken deployment must not share a
     * severity, or ERROR-based alerts would never fire for the second.
     *
     * <p>An unclassified non-success is treated as internal: an outcome we
     * could not name is by definition not an expected one.
     */
    private static LoggingEventBuilder levelFor(LlmExecutionCompletion completion) {
        if (completion.status() == LlmExecutionStatus.SUCCEEDED) {
            return LOGGER.atInfo();
        }
        LlmErrorCategory category = completion.errorCategory();
        return category == null || INTERNAL_FAILURE_CATEGORIES.contains(category)
                ? LOGGER.atError()
                : LOGGER.atWarn();
    }

    private static Integer tokens(LlmExecutionCompletion completion, Function<LlmTokenUsage, Integer> field) {
        return completion.tokenUsage() == null ? null : field.apply(completion.tokenUsage());
    }
}
