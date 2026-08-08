package com.iwrite.llm.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LlmExecutionAuditRepository extends JpaRepository<LlmExecutionAudit, UUID> {

    List<LlmExecutionAudit> findByTenantIdOrderByStartedAtDesc(UUID tenantId);

    Optional<LlmExecutionAudit> findByTraceId(UUID traceId);

    /**
     * Terminal update guarded by the current {@code STARTED} state. The first
     * completion wins; duplicate or delayed completions update zero rows, so a
     * recorded success can never be replaced by a later failure.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update LlmExecutionAudit audit
            set audit.status = :status,
                audit.model = :model,
                audit.completedAt = :completedAt,
                audit.latencyMs = :latencyMs,
                audit.errorCategory = :errorCategory,
                audit.inputTokens = :inputTokens,
                audit.outputTokens = :outputTokens,
                audit.totalTokens = :totalTokens,
                audit.estimatedCost = :estimatedCost,
                audit.costCurrency = :costCurrency,
                audit.fallbackUsed = :fallbackUsed
            where audit.id = :id
              and audit.status = :expectedStatus
            """)
    int completeIfStillStarted(
            @Param("id") UUID id,
            @Param("expectedStatus") LlmExecutionStatus expectedStatus,
            @Param("status") LlmExecutionStatus status,
            @Param("model") String model,
            @Param("completedAt") OffsetDateTime completedAt,
            @Param("latencyMs") Long latencyMs,
            @Param("errorCategory") LlmErrorCategory errorCategory,
            @Param("inputTokens") Integer inputTokens,
            @Param("outputTokens") Integer outputTokens,
            @Param("totalTokens") Integer totalTokens,
            @Param("estimatedCost") BigDecimal estimatedCost,
            @Param("costCurrency") String costCurrency,
            @Param("fallbackUsed") boolean fallbackUsed
    );
}
