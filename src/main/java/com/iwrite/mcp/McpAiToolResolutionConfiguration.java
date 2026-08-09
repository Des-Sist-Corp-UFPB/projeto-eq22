package com.iwrite.mcp;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Keeps the MCP server tool catalog separate from the tools available to the LLM itself.
 *
 * <p>Spring AI's default tool resolver also inspects {@code ToolCallbackProvider} beans. The MCP
 * server exposes its three tools through such a provider, so enabling a chat model caused the
 * model/tool infrastructure to eagerly resolve the MCP provider while the MCP tools were still
 * being created. That closes a dependency cycle through {@code SceneAnalysisService} and prevents
 * the application from starting.</p>
 *
 * <p>The scene-analysis assistant does not use model-invoked tools. While MCP is enabled, resolve
 * only explicit {@link ToolCallback} beans and deliberately exclude provider-backed MCP tools.
 * This preserves future explicit LLM tools without making the MCP server call back into itself.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "enabled", havingValue = "true")
class McpAiToolResolutionConfiguration {

    @Bean
    ToolCallbackResolver iwriteAiToolCallbackResolver(List<ToolCallback> toolCallbacks) {
        return new StaticToolCallbackResolver(toolCallbacks);
    }
}
