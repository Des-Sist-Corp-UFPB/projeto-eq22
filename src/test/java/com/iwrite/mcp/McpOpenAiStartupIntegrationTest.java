package com.iwrite.mcp;

import com.iwrite.scene.ai.OpenAiWritingAssistant;
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
 * Regression test for the startup cycle that occurred when the MCP server and a real chat model
 * were enabled together. No provider request is made; the test only proves that both subsystems can
 * be constructed in the same application context without feeding the MCP server's own tools back
 * into Spring AI's model-side tool resolver.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.ai.mcp.server.enabled=true",
                "spring.ai.model.chat=openai",
                "spring.ai.openai.api-key=test-not-a-real-key",
                "spring.ai.openai.base-url=http://127.0.0.1:1",
                "server.address=127.0.0.1",
                "iwrite.current-user.development.enabled=true",
                "server.shutdown=immediate"
        }
)
class McpOpenAiStartupIntegrationTest {

    @DynamicPropertySource
    static void testDatasourceProperties(DynamicPropertyRegistry registry) {
        TestDatabaseInitializer.prepareDatabase();
        registry.add("spring.datasource.url", TestDatabaseInitializer::testDbUrl);
        registry.add("spring.datasource.username", TestDatabaseInitializer::username);
        registry.add("spring.datasource.password", TestDatabaseInitializer::password);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
    }

    @Autowired
    private OpenAiWritingAssistant openAiWritingAssistant;

    @Autowired
    private ToolCallbackResolver toolCallbackResolver;

    @Autowired
    @Qualifier("iwriteMcpToolCallbacks")
    private ToolCallbackProvider mcpToolCallbacks;

    @Test
    void mcpAndOpenAiStartTogetherWithoutRegisteringMcpToolsWithTheModel() {
        assertThat(openAiWritingAssistant).isNotNull();

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
