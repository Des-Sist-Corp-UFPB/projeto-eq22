package com.iwrite.mcp;

import com.iwrite.audit.entity.AuditAction;
import com.iwrite.audit.entity.AuditResourceType;
import com.iwrite.audit.entity.AuditResult;
import com.iwrite.audit.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Envolve cada invocação MCP com auditoria de domínio e um log estruturado de
 * metadados (tool, resultado, duração, categoria de erro). Argumentos livres e
 * respostas nunca são registrados; a correlação com traces vem do contexto de
 * logging (MDC/OTel) já aplicado à requisição HTTP.
 */
@Service
@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "enabled", havingValue = "true")
public class McpInvocationSupport {

    private static final Logger log = LoggerFactory.getLogger("com.iwrite.mcp.invocations");

    private final AuditLogService auditLogService;

    public McpInvocationSupport(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    public <T> T invoke(
            String tool,
            AuditAction action,
            AuditResourceType resourceType,
            UUID resourceId,
            Supplier<T> operation
    ) {
        long startNanos = System.nanoTime();
        try {
            T result = operation.get();
            auditLogService.record(action, resourceType, resourceId, AuditResult.SUCCEEDED);
            log.info("mcp_invocation tool={} resourceType={} outcome=success durationMs={}",
                    tool, resourceType, elapsedMillis(startNanos));
            return result;
        } catch (RuntimeException exception) {
            McpToolException sanitized = McpToolException.from(exception);
            try {
                auditLogService.record(action, resourceType, resourceId, AuditResult.FAILED);
            } catch (RuntimeException auditFailure) {
                sanitized.addSuppressed(auditFailure);
            }
            log.info("mcp_invocation tool={} resourceType={} outcome=error errorCategory={} durationMs={}",
                    tool, resourceType, sanitized.category(), elapsedMillis(startNanos));
            throw sanitized;
        }
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
