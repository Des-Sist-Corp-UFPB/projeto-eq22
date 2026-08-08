package com.iwrite.llm.gateway;

import com.iwrite.llm.audit.LlmErrorCategory;
import com.iwrite.llm.audit.LlmExecutionStatus;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

/**
 * Maps execution failures to explicit statuses and stable error categories.
 * Only Spring AI's provider-neutral exception types and JDK timeout types are
 * inspected here; provider-specific exceptions fall into the generic buckets.
 */
@Component
public class LlmErrorClassifier {

    private static final int MAX_CAUSE_DEPTH = 10;

    public LlmErrorClassification classify(Throwable failure) {
        if (failure instanceof LlmFeatureDisabledException) {
            return new LlmErrorClassification(LlmExecutionStatus.DISABLED, LlmErrorCategory.FEATURE_DISABLED);
        }
        if (failure instanceof LlmInvalidResponseException) {
            return new LlmErrorClassification(
                    LlmExecutionStatus.INVALID_RESPONSE,
                    LlmErrorCategory.INVALID_STRUCTURED_RESPONSE
            );
        }
        if (failure instanceof LlmConfigurationException) {
            return new LlmErrorClassification(LlmExecutionStatus.FAILED, LlmErrorCategory.CONFIGURATION_ERROR);
        }
        if (causedByTimeout(failure)) {
            return new LlmErrorClassification(LlmExecutionStatus.TIMED_OUT, LlmErrorCategory.PROVIDER_TIMEOUT);
        }
        if (failure instanceof TransientAiException || failure instanceof ResourceAccessException) {
            return new LlmErrorClassification(LlmExecutionStatus.UNAVAILABLE, LlmErrorCategory.PROVIDER_UNAVAILABLE);
        }
        if (failure instanceof NonTransientAiException) {
            return new LlmErrorClassification(
                    LlmExecutionStatus.FAILED,
                    LlmErrorCategory.PROVIDER_REQUEST_REJECTED
            );
        }
        return new LlmErrorClassification(LlmExecutionStatus.FAILED, LlmErrorCategory.INTERNAL_EXECUTION_ERROR);
    }

    private boolean causedByTimeout(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
