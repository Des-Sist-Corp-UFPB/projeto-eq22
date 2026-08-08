package com.iwrite.mcp;

import com.iwrite.scene.ai.AnthropicWritingAssistant;
import com.iwrite.support.TestDatabaseInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for the same MCP/model startup boundary exercised by
 * {@link McpOpenAiStartupIntegrationTest}, now with Anthropic selected. The
 * provider endpoint is deliberately unreachable because this test validates
 * context construction only and must never make a paid external request.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.ai.mcp.server.enabled=true",
                "spring.ai.model.chat=anthropic",
                "spring.ai.anthropic.api-key=test-not-a-real-key",
                "spring.ai.anthropic.base-url=http://127.0.0.1:1",
                "spring.ai.anthropic.chat.options.model=claude-sonnet-4-20250514",
                "server.address=127.0.0.1",
                "iwrite.current-user.development.enabled=true",
                "server.shutdown=immediate"
        }
)
class McpAnthropicStartupIntegrationTest {

    @DynamicPropertySource
    static void testDatasourceProperties(DynamicPropertyRegistry registry) {
        TestDatabaseInitializer.prepareDatabase();
        registry.add("spring.datasource.url", TestDatabaseInitializer::testDbUrl);
        registry.add("spring.datasource.username", TestDatabaseInitializer::username);
        registry.add("spring.datasource.password", TestDatabaseInitializer::password);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
    }

    @Autowired
    private AnthropicWritingAssistant anthropicWritingAssistant;

    @Autowired
    private ToolCallbackResolver toolCallbackResolver;

    @Autowired
    @Qualifier("iwriteMcpToolCallbacks")
    private ToolCallbackProvider mcpToolCallbacks;

    @Test
    void mcpAndAnthropicStartTogetherWithoutRegisteringMcpToolsWithTheModel() {
        assertThat(anthropicWritingAssistant).isNotNull();
        assertThat(anthropicWritingAssistant.provider()).isEqualTo("anthropic");
        assertThat(anthropicWritingAssistant.model()).isEqualTo("claude-sonnet-4-20250514");

        assertThat(Arrays.stream(mcpToolCallbacks.getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name())
                .toList())
                .containsExactlyInAnyOrder(
                        "listar_livros_acessiveis",
                        "obter_outline_livro",
                        "analisar_cena");

        assertThat(toolCallbackResolver.resolve("analisar_cena")).isNull();
        assertThat(toolCallbackResolver.resolve("listar_livros_acessiveis")).isNull();
        assertThat(toolCallbackResolver.resolve("obter_outline_livro")).isNull();
    }
}
