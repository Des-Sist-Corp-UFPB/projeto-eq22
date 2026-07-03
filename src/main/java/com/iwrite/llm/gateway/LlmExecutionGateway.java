package com.iwrite.llm.gateway;

import com.iwrite.llm.audit.LlmErrorCategory;
import com.iwrite.llm.audit.LlmExecutionAuditRecorder;
import com.iwrite.llm.audit.LlmExecutionCompletion;
import com.iwrite.llm.audit.LlmExecutionStart;
import com.iwrite.llm.audit.LlmExecutionStatus;
import com.iwrite.llm.cost.LlmCostEstimate;
import com.iwrite.llm.cost.LlmCostEstimator;
import com.iwrite.user.context.CurrentUserProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Single instrumented entry point for every product LLM execution.
 *
 * <p>Responsibilities are fixed here so features cannot diverge: audit-start
 * persistence, provider invocation, latency measurement, error classification,
 * token usage collection, optional cost estimation, terminal audit persistence,
 * and sanitized logging with one trace ID per logical execution.
 *
 * <p>Transaction contract: this method must be called without an active
 * database transaction, so no connection is held open during the external
 * provider call. Audit writes run in their own short transactions.
 *
 * <p>Failure contract: if the start audit cannot be persisted the execution is
 * aborted before the provider is invoked (fail-closed auditing). If a terminal
 * audit write fails after the provider call, the product outcome is preserved
 * and the persistence failure is logged with the trace ID.
 */
@Component
public class LlmExecutionGateway {

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmExecutionGateway.class);
    private static final String TRACE_MDC_KEY = "llmTraceId";

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

        try (MDC.MDCCloseable ignored = MDC.putCloseable(TRACE_MDC_KEY, traceId.toString())) {
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
                logOutcome(spec, traceId, completion);
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
            logOutcome(spec, traceId, completion);
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
                    "LLM execution audit start failed feature={} provider={} traceId={} failureType={}",
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
                        "LLM execution audit already finalized; keeping first terminal state traceId={} auditId={}",
                        traceId,
                        auditId
                );
            }
        } catch (RuntimeException persistenceFailure) {
            LOGGER.error(
                    "LLM execution audit completion failed traceId={} auditId={} status={} failureType={}",
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
                    "LLM cost estimation failed; persisting execution without cost traceId={} failureType={}",
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
        LOGGER.warn("Ignoring invalid provider-reported model metadata traceId={}", traceId);
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

    private void logOutcome(LlmExecutionSpec spec, UUID traceId, LlmExecutionCompletion completion) {
        LOGGER.info(
                "LLM execution feature={} provider={} model={} promptVersion={} traceId={} status={} "
                        + "errorCategory={} latencyMs={} inputTokens={} outputTokens={} totalTokens={} fallbackUsed={}",
                spec.feature(),
                spec.provider(),
                completion.model(),
                spec.promptVersion(),
                traceId,
                completion.status(),
                completion.errorCategory(),
                completion.latencyMs(),
                completion.tokenUsage() == null ? null : completion.tokenUsage().inputTokens(),
                completion.tokenUsage() == null ? null : completion.tokenUsage().outputTokens(),
                completion.tokenUsage() == null ? null : completion.tokenUsage().totalTokens(),
                completion.fallbackUsed()
        );
    }
}
