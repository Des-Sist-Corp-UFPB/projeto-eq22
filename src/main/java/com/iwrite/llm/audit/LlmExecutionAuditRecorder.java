package com.iwrite.llm.audit;

import com.iwrite.llm.LlmTokenUsage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Persists LLM execution audit rows in independent transactions so that no
 * database transaction stays open across the external provider call and so
 * audit rows survive rollbacks of surrounding work.
 */
@Service
public class LlmExecutionAuditRecorder {

    private final LlmExecutionAuditRepository repository;

    public LlmExecutionAuditRecorder(LlmExecutionAuditRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID recordStart(LlmExecutionStart start) {
        LlmExecutionAudit audit = new LlmExecutionAudit(
                start.tenantId(),
                start.userId(),
                start.feature(),
                start.provider(),
                start.model(),
                start.promptVersion(),
                start.traceId(),
                start.resourceType(),
                start.resourceId(),
                start.startedAt()
        );
        return repository.save(audit).getId();
    }

    /**
     * Applies the terminal state at most once. Returns {@code false} when the
     * execution was already completed, leaving the first terminal state intact.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean complete(UUID auditId, LlmExecutionCompletion completion) {
        LlmTokenUsage tokenUsage = completion.tokenUsage();
        int updatedRows = repository.completeIfStillStarted(
                auditId,
                LlmExecutionStatus.STARTED,
                completion.status(),
                completion.model(),
                completion.completedAt(),
                completion.latencyMs(),
                completion.errorCategory(),
                tokenUsage == null ? null : tokenUsage.inputTokens(),
                tokenUsage == null ? null : tokenUsage.outputTokens(),
                tokenUsage == null ? null : tokenUsage.totalTokens(),
                completion.estimatedCost() == null ? null : completion.estimatedCost().amount(),
                completion.estimatedCost() == null ? null : completion.estimatedCost().currency(),
                completion.fallbackUsed()
        );
        return updatedRows == 1;
    }
}
