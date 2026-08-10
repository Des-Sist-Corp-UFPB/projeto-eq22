package com.iwrite.scene.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenAiWritingAssistantContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(OpenAiWritingAssistantContextConfiguration.class)
            .withPropertyValues(
                    "spring.ai.model.chat=openai",
                    "iwrite.ai.openai.chat.options.temperature=0.2",
                    "iwrite.ai.openai.chat.options.max-tokens=4096",
                    "iwrite.ai.openai.chat.options.reasoning-effort=none");

    @Test
    void createsOpenAiWritingAssistantWhenAiChatIsEnabled() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OpenAiWritingAssistant.class);

            ChatClient chatClient = context.getBean(ChatClient.class);
            verify(chatClient, never()).prompt();
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({OpenAiWritingAssistant.class, OpenAiChatOptionsConfiguration.class})
    static class OpenAiWritingAssistantContextConfiguration {

        @Bean
        ChatClient.Builder chatClientBuilder(ChatClient chatClient) {
            ChatClient.Builder builder = mock(ChatClient.Builder.class);
            when(builder.build()).thenReturn(chatClient);
            return builder;
        }

        @Bean
        ChatClient chatClient() {
            return mock(ChatClient.class);
        }

        @Bean
        OpenAiChatProperties openAiChatProperties() {
            OpenAiChatProperties properties = new OpenAiChatProperties();
            properties.getOptions().setModel("gemini-2.5-flash");
            return properties;
        }
    }
}
