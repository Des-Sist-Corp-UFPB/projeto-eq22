package com.iwrite.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iwrite.audit.entity.AuditAction;
import com.iwrite.audit.entity.AuditResourceType;
import com.iwrite.outline.dto.BookOutlineResponse;
import com.iwrite.outline.service.OutlineService;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Registra as tools e o resource MCP. Transporte: HTTP/SSE do starter WebMVC na
 * mesma porta da API. Desabilitado por padrão ({@code IWRITE_MCP_ENABLED=false})
 * para o servidor não ficar exposto anonimamente em ambientes remotos.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "enabled", havingValue = "true")
public class IwriteMcpServerConfiguration {

    public static final String OUTLINE_URI_TEMPLATE = "iwrite://books/{bookId}/outline";

    private static final Pattern OUTLINE_URI_PATTERN = Pattern.compile("^iwrite://books/([^/]+)/outline$");

    @Bean
    ToolCallbackProvider iwriteMcpToolCallbacks(IwriteMcpTools iwriteMcpTools) {
        return MethodToolCallbackProvider.builder().toolObjects(iwriteMcpTools).build();
    }

    @Bean
    McpServerFeatures.SyncResourceTemplateSpecification iwriteOutlineResourceTemplate(
            OutlineService outlineService,
            McpInvocationSupport invocationSupport,
            ObjectMapper objectMapper
    ) {
        return new McpServerFeatures.SyncResourceTemplateSpecification(
                McpSchema.ResourceTemplate.builder()
                        .uriTemplate(OUTLINE_URI_TEMPLATE)
                        .name("outline_livro")
                        .description("Outline (partes, capítulos e cenas) de um livro acessível ao usuário atual. "
                                + "Somente metadados e estrutura; sem conteúdo de cenas.")
                        .mimeType("application/json")
                        .build(),
                (exchange, request) -> readOutline(outlineService, invocationSupport, objectMapper, request)
        );
    }

    @Bean
    SmartInitializingSingleton iwriteMcpOutlineResourceRegistrar(
            McpSyncServer mcpSyncServer,
            McpServerFeatures.SyncResourceTemplateSpecification iwriteOutlineResourceTemplate
    ) {
        return () -> mcpSyncServer.addResourceTemplate(iwriteOutlineResourceTemplate);
    }

    private McpSchema.ReadResourceResult readOutline(
            OutlineService outlineService,
            McpInvocationSupport invocationSupport,
            ObjectMapper objectMapper,
            McpSchema.ReadResourceRequest request
    ) {
        UUID bookId = parseBookId(request.uri());
        BookOutlineResponse outline = invocationSupport.invoke(
                "resource:outline_livro",
                AuditAction.MCP_BOOK_OUTLINE_VIEWED,
                AuditResourceType.BOOK,
                bookId,
                () -> outlineService.getOutline(bookId)
        );
        return new McpSchema.ReadResourceResult(java.util.List.of(
                new McpSchema.TextResourceContents(request.uri(), "application/json", toJson(objectMapper, outline))
        ));
    }

    private UUID parseBookId(String uri) {
        Matcher matcher = OUTLINE_URI_PATTERN.matcher(uri == null ? "" : uri.trim());
        if (!matcher.matches()) {
            throw new McpToolException(
                    McpToolException.CATEGORY_INVALID_REQUEST,
                    "URI de resource inválida. Use " + OUTLINE_URI_TEMPLATE + "."
            );
        }
        try {
            return UUID.fromString(matcher.group(1));
        } catch (IllegalArgumentException exception) {
            throw new McpToolException(
                    McpToolException.CATEGORY_INVALID_REQUEST,
                    "bookId da URI deve ser um UUID válido."
            );
        }
    }

    private String toJson(ObjectMapper objectMapper, BookOutlineResponse outline) {
        try {
            return objectMapper.writeValueAsString(outline);
        } catch (JsonProcessingException exception) {
            throw new McpToolException(McpToolException.CATEGORY_INTERNAL, "Erro interno inesperado.");
        }
    }
}
