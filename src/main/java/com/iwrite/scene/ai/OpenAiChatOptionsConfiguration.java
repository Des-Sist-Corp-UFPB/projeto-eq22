package com.iwrite.scene.ai;

import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "openai")
@EnableConfigurationProperties(OpenAiChatGenerationProperties.class)
class OpenAiChatOptionsConfiguration {

    @Bean
    static BeanPostProcessor openAiChatDefaultOptionsSanitizer() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof OpenAiChatProperties properties) {
                    sanitize(properties.getOptions());
                }
                return bean;
            }
        };
    }

    private static void sanitize(OpenAiChatOptions options) {
        options.setTemperature(null);
        options.setMaxTokens(null);
        options.setMaxCompletionTokens(null);
        options.setReasoningEffort(null);
    }
}
