package com.iwrite.llm.gateway;

import com.iwrite.audit.entity.AuditResourceType;
import com.iwrite.llm.LlmFeature;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Bounded metadata describing one LLM execution. Field formats are strict on
 * purpose: identifiers cannot contain whitespace, so prompt text, manuscript
 * content, or credentials cannot be smuggled into the audit trail through
 * this record.
 *
 * <p>The prompt version is a stable readable identifier such as
 * {@code scene-analysis:v1}, never the prompt text itself.
 */
public record LlmExecutionSpec(
        LlmFeature feature,
        String provider,
        String model,
        String promptVersion,
        AuditResourceType resourceType,
        UUID resourceId
) {

    private static final Pattern PROVIDER_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern MODEL_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");
    private static final Pattern PROMPT_VERSION_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,54}:v[0-9]{1,8}");

    public LlmExecutionSpec {
        Objects.requireNonNull(feature, "feature is required");
        require(PROVIDER_PATTERN, provider, "provider");
        if (model != null) {
            require(MODEL_PATTERN, model, "model");
        }
        require(PROMPT_VERSION_PATTERN, promptVersion, "promptVersion");
        if (resourceId != null && resourceType == null) {
            throw new IllegalArgumentException("resourceType is required when resourceId is provided.");
        }
    }

    public static LlmExecutionSpec of(LlmFeature feature, String provider, String model, String promptVersion) {
        return new LlmExecutionSpec(feature, provider, model, promptVersion, null, null);
    }

    public LlmExecutionSpec withResource(AuditResourceType resourceType, UUID resourceId) {
        return new LlmExecutionSpec(feature, provider, model, promptVersion, resourceType, resourceId);
    }

    private static void require(Pattern pattern, String value, String field) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a short identifier without whitespace.");
        }
    }
}
