package com.iwrite.llm.cost;

import com.iwrite.llm.LlmTokenUsage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ConfiguredLlmCostEstimatorTest {

    @Test
    void estimatesConfiguredProviderAndModelDeterministically() {
        ConfiguredLlmCostEstimator estimator = estimator(true, "USD", "openai", "gpt-4o-mini", "0.15", "0.60");

        Optional<LlmCostEstimate> estimate = estimator.estimate(
                "openai",
                "gpt-4o-mini",
                new LlmTokenUsage(1_234, 567, 1_801));

        assertThat(estimate).contains(new LlmCostEstimate(new BigDecimal("0.000525"), "USD"));
    }

    @Test
    void missingPricingProducesEmptyInsteadOfZero() {
        ConfiguredLlmCostEstimator estimator = estimator(true, "USD", "openai", "gpt-4o-mini", "0.15", "0.60");
        LlmTokenUsage usage = new LlmTokenUsage(100, 100, 200);

        assertThat(estimator.estimate("openai", "unpriced-model", usage)).isEmpty();
        assertThat(estimator.estimate("unpriced-provider", "gpt-4o-mini", usage)).isEmpty();
    }

    @Test
    void partiallyConfiguredModelPricingProducesEmpty() {
        ConfiguredLlmCostEstimator estimator = estimator(true, "USD", "openai", "gpt-4o-mini", "0.15", null);

        assertThat(estimator.estimate("openai", "gpt-4o-mini", new LlmTokenUsage(100, 100, 200))).isEmpty();
    }

    @Test
    void disabledEstimationProducesEmpty() {
        ConfiguredLlmCostEstimator estimator = estimator(false, "USD", "openai", "gpt-4o-mini", "0.15", "0.60");

        assertThat(estimator.estimate("openai", "gpt-4o-mini", new LlmTokenUsage(100, 100, 200))).isEmpty();
    }

    @Test
    void incompleteTokenUsageProducesEmpty() {
        ConfiguredLlmCostEstimator estimator = estimator(true, "USD", "openai", "gpt-4o-mini", "0.15", "0.60");

        assertThat(estimator.estimate("openai", "gpt-4o-mini", null)).isEmpty();
        assertThat(estimator.estimate("openai", "gpt-4o-mini", new LlmTokenUsage(null, 100, null))).isEmpty();
        assertThat(estimator.estimate("openai", "gpt-4o-mini", new LlmTokenUsage(100, null, null))).isEmpty();
        assertThat(estimator.estimate("openai", null, new LlmTokenUsage(100, 100, 200))).isEmpty();
    }

    @Test
    void usesConfiguredCurrency() {
        ConfiguredLlmCostEstimator estimator = estimator(true, "BRL", "openai", "gpt-4o-mini", "1.00", "1.00");

        Optional<LlmCostEstimate> estimate = estimator.estimate(
                "openai",
                "gpt-4o-mini",
                new LlmTokenUsage(1_000_000, 1_000_000, 2_000_000));

        assertThat(estimate).contains(new LlmCostEstimate(new BigDecimal("2.000000"), "BRL"));
    }

    private ConfiguredLlmCostEstimator estimator(
            boolean enabled,
            String currency,
            String provider,
            String model,
            String inputPerMillion,
            String outputPerMillion
    ) {
        LlmPricingProperties properties = new LlmPricingProperties();
        properties.setEnabled(enabled);
        properties.setCurrency(currency);

        LlmPricingProperties.ModelPricing modelPricing = new LlmPricingProperties.ModelPricing();
        modelPricing.setInputPerMillionTokens(inputPerMillion == null ? null : new BigDecimal(inputPerMillion));
        modelPricing.setOutputPerMillionTokens(outputPerMillion == null ? null : new BigDecimal(outputPerMillion));

        LlmPricingProperties.ProviderPricing providerPricing = new LlmPricingProperties.ProviderPricing();
        providerPricing.setModels(Map.of(model, modelPricing));
        properties.setProviders(Map.of(provider, providerPricing));

        return new ConfiguredLlmCostEstimator(properties);
    }
}
