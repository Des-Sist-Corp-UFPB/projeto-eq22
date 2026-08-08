package com.iwrite.llm.cost;

import com.iwrite.llm.LlmTokenUsage;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Estimates cost from the configured pricing table. Missing pricing, disabled
 * estimation, or incomplete token usage produce an empty result instead of a
 * misleading zero.
 */
@Component
public class ConfiguredLlmCostEstimator implements LlmCostEstimator {

    private static final BigDecimal TOKENS_PER_PRICING_UNIT = BigDecimal.valueOf(1_000_000);
    private static final int COST_SCALE = 6;

    private final LlmPricingProperties pricingProperties;

    public ConfiguredLlmCostEstimator(LlmPricingProperties pricingProperties) {
        this.pricingProperties = pricingProperties;
    }

    @Override
    public Optional<LlmCostEstimate> estimate(String provider, String model, LlmTokenUsage tokenUsage) {
        if (!pricingProperties.isEnabled()
                || provider == null
                || model == null
                || tokenUsage == null
                || tokenUsage.inputTokens() == null
                || tokenUsage.outputTokens() == null) {
            return Optional.empty();
        }

        LlmPricingProperties.ProviderPricing providerPricing = pricingProperties.getProviders().get(provider);
        if (providerPricing == null) {
            return Optional.empty();
        }
        LlmPricingProperties.ModelPricing modelPricing = providerPricing.getModels().get(model);
        if (modelPricing == null
                || modelPricing.getInputPerMillionTokens() == null
                || modelPricing.getOutputPerMillionTokens() == null) {
            return Optional.empty();
        }

        BigDecimal inputCost = costOf(tokenUsage.inputTokens(), modelPricing.getInputPerMillionTokens());
        BigDecimal outputCost = costOf(tokenUsage.outputTokens(), modelPricing.getOutputPerMillionTokens());
        BigDecimal amount = inputCost.add(outputCost).setScale(COST_SCALE, RoundingMode.HALF_UP);
        return Optional.of(new LlmCostEstimate(amount, pricingProperties.getCurrency()));
    }

    private BigDecimal costOf(int tokens, BigDecimal pricePerMillionTokens) {
        return pricePerMillionTokens
                .multiply(BigDecimal.valueOf(tokens))
                .divide(TOKENS_PER_PRICING_UNIT, COST_SCALE + 4, RoundingMode.HALF_UP);
    }
}
