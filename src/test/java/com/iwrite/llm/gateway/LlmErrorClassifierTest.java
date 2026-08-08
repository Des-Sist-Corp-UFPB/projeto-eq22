package com.iwrite.llm.gateway;

import com.iwrite.llm.audit.LlmErrorCategory;
import com.iwrite.llm.audit.LlmExecutionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class LlmErrorClassifierTest {

    private final LlmErrorClassifier classifier = new LlmErrorClassifier();

    @Test
    void classifiesDisabledFeature() {
        assertThat(classifier.classify(new LlmFeatureDisabledException()))
                .isEqualTo(new LlmErrorClassification(LlmExecutionStatus.DISABLED, LlmErrorCategory.FEATURE_DISABLED));
    }

    @Test
    void classifiesInvalidStructuredResponse() {
        assertThat(classifier.classify(new LlmInvalidResponseException(new IllegalArgumentException("raw body"))))
                .isEqualTo(new LlmErrorClassification(
                        LlmExecutionStatus.INVALID_RESPONSE,
                        LlmErrorCategory.INVALID_STRUCTURED_RESPONSE));
    }

    @Test
    void classifiesConfigurationError() {
        assertThat(classifier.classify(new LlmConfigurationException("missing model")))
                .isEqualTo(new LlmErrorClassification(LlmExecutionStatus.FAILED, LlmErrorCategory.CONFIGURATION_ERROR));
    }

    @Test
    void classifiesNestedSocketTimeoutAsProviderTimeout() {
        ResourceAccessException failure = new ResourceAccessException(
                "I/O error",
                new IOException(new SocketTimeoutException("read timed out")));

        assertThat(classifier.classify(failure))
                .isEqualTo(new LlmErrorClassification(LlmExecutionStatus.TIMED_OUT, LlmErrorCategory.PROVIDER_TIMEOUT));
    }

    @Test
    void classifiesTimeoutExceptionAsProviderTimeout() {
        assertThat(classifier.classify(new RuntimeException(new TimeoutException("deadline"))))
                .isEqualTo(new LlmErrorClassification(LlmExecutionStatus.TIMED_OUT, LlmErrorCategory.PROVIDER_TIMEOUT));
    }

    @Test
    void classifiesTransientProviderFailureAsUnavailable() {
        assertThat(classifier.classify(new TransientAiException("HTTP 503")))
                .isEqualTo(new LlmErrorClassification(
                        LlmExecutionStatus.UNAVAILABLE,
                        LlmErrorCategory.PROVIDER_UNAVAILABLE));
    }

    @Test
    void classifiesConnectionFailureAsUnavailable() {
        assertThat(classifier.classify(new ResourceAccessException("connection refused")))
                .isEqualTo(new LlmErrorClassification(
                        LlmExecutionStatus.UNAVAILABLE,
                        LlmErrorCategory.PROVIDER_UNAVAILABLE));
    }

    @Test
    void classifiesNonTransientProviderFailureAsRequestRejected() {
        assertThat(classifier.classify(new NonTransientAiException("HTTP 400")))
                .isEqualTo(new LlmErrorClassification(
                        LlmExecutionStatus.FAILED,
                        LlmErrorCategory.PROVIDER_REQUEST_REJECTED));
    }

    @Test
    void classifiesUnknownFailureAsInternalExecutionError() {
        assertThat(classifier.classify(new IllegalStateException("boom")))
                .isEqualTo(new LlmErrorClassification(
                        LlmExecutionStatus.FAILED,
                        LlmErrorCategory.INTERNAL_EXECUTION_ERROR));
    }
}
