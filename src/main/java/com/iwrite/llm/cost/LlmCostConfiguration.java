package com.iwrite.llm.cost;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LlmPricingProperties.class)
class LlmCostConfiguration {
}
