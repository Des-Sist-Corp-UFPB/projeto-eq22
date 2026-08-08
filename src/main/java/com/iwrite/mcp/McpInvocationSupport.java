package com.iwrite.mcp;

import com.iwrite.audit.entity.AuditAction;
import com.iwrite.audit.entity.AuditResourceType;
import com.iwrite.audit.entity.AuditResult;
import com.iwrite.audit.service.AuditLogService;
import com.iwrite.llm.gateway.LlmExecutionException;
import com.iwrite.observability.BusinessTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Envolve cada invocação MCP com auditoria de domínio e um evento estruturado de
 * metadados (tool, resultado, duração, categoria de erro) em key-value pairs do
 * SLF4J 2, exportados como atributos OTLP pelo Java Agent. Argumentos livres,
 * respostas, títulos e IDs enviados pelo cliente nunca são registrados; a
 * correlação com traces vem do contexto de logging já aplicado à requisição HTTP.
 *
 * <p>{@code tool} e {@code resourceType} são valores fixos declarados no código
 * das ferramentas MCP, nunca texto vindo do cliente.
 */
@Service
@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "enabled", havingValue = "true")
public class McpInvocationSupport {

    private static final Logger log = LoggerFactory.getLogger("com.iwrite.mcp.invocations");

    private static final String EVENT_NAME = "iwrite.mcp.invocation";
    private static final String EVENT_MESSAGE = "MCP invocation completed";

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
            log.atInfo()
                    .addKeyValue(BusinessTelemetry.EVENT_NAME_KEY, EVENT_NAME)
                    .addKeyValue("iwrite.mcp.tool", tool)
                    .addKeyValue("iwrite.mcp.resource_type", resourceType.name())
                    .addKeyValue("iwrite.result", BusinessTelemetry.RESULT_SUCCESS)
                    .addKeyValue(BusinessTelemetry.DURATION_MS_KEY, elapsedMillis(startNanos))
                    .log(EVENT_MESSAGE);
            return result;
        } catch (RuntimeException exception) {
            McpToolException sanitized = McpToolException.from(exception);
            boolean auditWriteFailed = false;
            try {
                auditLogService.record(action, resourceType, resourceId, AuditResult.FAILED);
            } catch (RuntimeException auditFailure) {
                auditWriteFailed = true;
                sanitized.addSuppressed(auditFailure);
            }
            /*
             * Erro tratado e já classificado, sem throwable e sem mensagem. O
             * nível vem da categoria: "internal" é uma exceção não reconhecida,
             * ou seja, defeito do servidor, e precisa se distinguir de
             * not_found/invalid_request/unavailable, que são desfechos
             * esperados. Sem isso um alerta por ERROR nunca veria um defeito.
             * Uma falha ao gravar a auditoria também força ERROR, mesmo com a
             * operação original classificada como esperada — ver levelFor.
             */
            levelFor(exception, sanitized.category(), auditWriteFailed)
                    .addKeyValue(BusinessTelemetry.EVENT_NAME_KEY, EVENT_NAME)
                    .addKeyValue("iwrite.mcp.tool", tool)
                    .addKeyValue("iwrite.mcp.resource_type", resourceType.name())
                    .addKeyValue("iwrite.result", BusinessTelemetry.RESULT_FAILURE)
                    .addKeyValue("iwrite.error.category", sanitized.category())
                    .addKeyValue(BusinessTelemetry.DURATION_MS_KEY, elapsedMillis(startNanos))
                    .log(EVENT_MESSAGE);
            throw sanitized;
        }
    }

    /**
     * Severidade vem da falha real; a categoria <em>exportada</em> continua
     * sendo a sanitizada, voltada ao cliente. Três fontes independentes podem
     * forçar ERROR:
     *
     * <ol>
     *   <li>categoria pública {@code internal} — exceção não reconhecida;</li>
     *   <li>categoria LLM interna/configuração/auditoria — {@code
     *       McpToolException.from} colapsa toda {@code LlmExecutionException}
     *       em {@code unavailable}, o que é correto para o cliente MCP mas
     *       apagaria a diferença entre um provider fora do ar e um deployment
     *       quebrado, então a exceção original é consultada antes da
     *       sanitização;</li>
     *   <li>falha ao gravar a própria auditoria MCP — se {@code
     *       AuditResult.FAILED} não pôde ser persistido, uma falha de
     *       infraestrutura real ficaria escondida atrás da severidade do
     *       resultado original (ex.: {@code not_found} → {@code WARN}), e um
     *       alerta por ERROR nunca a veria.</li>
     * </ol>
     */
    private LoggingEventBuilder levelFor(RuntimeException original, String category, boolean auditWriteFailed) {
        boolean internal = auditWriteFailed
                || McpToolException.CATEGORY_INTERNAL.equals(category)
                || (original instanceof LlmExecutionException llmFailure
                    && llmFailure.getErrorCategory() != null
                    && llmFailure.getErrorCategory().isInternalFailure());
        return internal ? log.atError() : log.atWarn();
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
