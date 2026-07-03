package com.iwrite.llm.cost;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deployment-provided pricing table, e.g.:
 *
 * <pre>
 * iwrite:
 *   ai:
 *     audit:
 *       pricing:
 *         enabled: true
 *         currency: USD
 *         providers:
 *           openai:
 *             models:
 *               "[gpt-4o-mini]":
 *                 input-per-million-tokens: 0.15
 *                 output-per-million-tokens: 0.60
 * </pre>
 */
@ConfigurationProperties(prefix = "iwrite.ai.audit.pricing")
public class LlmPricingProperties {

    private boolean enabled = false;
    private String currency = "USD";
    private Map<String, ProviderPricing> providers = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Map<String, ProviderPricing> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, ProviderPricing> providers) {
        this.providers = providers;
    }

    public static class ProviderPricing {

        private Map<String, ModelPricing> models = new LinkedHashMap<>();

        public Map<String, ModelPricing> getModels() {
            return models;
        }

        public void setModels(Map<String, ModelPricing> models) {
            this.models = models;
        }
    }

    public static class ModelPricing {

        private BigDecimal inputPerMillionTokens;
        private BigDecimal outputPerMillionTokens;

        public BigDecimal getInputPerMillionTokens() {
            return inputPerMillionTokens;
        }

        public void setInputPerMillionTokens(BigDecimal inputPerMillionTokens) {
            this.inputPerMillionTokens = inputPerMillionTokens;
        }

        public BigDecimal getOutputPerMillionTokens() {
            return outputPerMillionTokens;
        }

        public void setOutputPerMillionTokens(BigDecimal outputPerMillionTokens) {
            this.outputPerMillionTokens = outputPerMillionTokens;
        }
    }
}
