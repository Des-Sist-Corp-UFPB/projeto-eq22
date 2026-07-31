package com.iwrite.mcp;

import com.iwrite.common.exception.BadRequestException;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.common.exception.ServiceUnavailableException;
import com.iwrite.llm.gateway.LlmConfigurationException;
import com.iwrite.llm.gateway.LlmExecutionException;
import com.iwrite.llm.gateway.LlmFeatureDisabledException;
import com.iwrite.llm.gateway.LlmInvalidResponseException;

/**
 * Erro estruturado e sanitizado exposto ao cliente MCP. A mensagem é um JSON
 * compacto com categoria enumerada; nunca contém stack trace, conteúdo de
 * manuscrito nem detalhes internos do servidor.
 */
public class McpToolException extends RuntimeException {

    public static final String CATEGORY_NOT_FOUND = "not_found";
    public static final String CATEGORY_INVALID_REQUEST = "invalid_request";
    public static final String CATEGORY_UNAVAILABLE = "unavailable";
    public static final String CATEGORY_RATE_LIMITED = "rate_limited";
    public static final String CATEGORY_INTERNAL = "internal";

    private final String category;

    public McpToolException(String category, String safeMessage) {
        super("{\"error\":{\"category\":\"" + escape(category) + "\",\"message\":\"" + escape(safeMessage) + "\"}}");
        this.category = category;
    }

    public String category() {
        return category;
    }

    public static McpToolException from(RuntimeException exception) {
        if (exception instanceof McpToolException mcpToolException) {
            return mcpToolException;
        }
        if (exception instanceof ResourceNotFoundException) {
            return new McpToolException(CATEGORY_NOT_FOUND, "Recurso não encontrado ou inacessível.");
        }
        if (exception instanceof BadRequestException) {
            return new McpToolException(CATEGORY_INVALID_REQUEST, exception.getMessage());
        }
        if (exception instanceof LlmFeatureDisabledException
                || exception instanceof LlmConfigurationException
                || exception instanceof LlmInvalidResponseException
                || exception instanceof LlmExecutionException
                || exception instanceof ServiceUnavailableException) {
            return new McpToolException(CATEGORY_UNAVAILABLE, "A operação está indisponível no momento. Tente novamente mais tarde.");
        }
        return new McpToolException(CATEGORY_INTERNAL, "Erro interno inesperado.");
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }
}
