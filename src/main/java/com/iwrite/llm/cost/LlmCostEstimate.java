package com.iwrite.llm.cost;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.regex.Pattern;

public record LlmCostEstimate(BigDecimal amount, String currency) {

    /** Matches the varchar(8) audit column; typically an ISO 4217 code. */
    private static final Pattern CURRENCY_PATTERN = Pattern.compile("[A-Z]{3,8}");

    public LlmCostEstimate {
        Objects.requireNonNull(amount, "amount is required");
        Objects.requireNonNull(currency, "currency is required");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("Estimated cost must not be negative.");
        }
        if (!CURRENCY_PATTERN.matcher(currency).matches()) {
            throw new IllegalArgumentException("currency must be a short uppercase code such as USD.");
        }
    }
}
