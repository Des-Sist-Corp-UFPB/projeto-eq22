package com.iwrite.scene.service;

import com.iwrite.common.exception.BadRequestException;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.export.service.TipTapPlainTextRenderer;
import com.iwrite.audit.entity.AuditResourceType;
import com.iwrite.llm.LlmFeature;
import com.iwrite.llm.gateway.LlmCallResult;
import com.iwrite.llm.gateway.LlmExecutionException;
import com.iwrite.llm.gateway.LlmExecutionGateway;
import com.iwrite.llm.gateway.LlmExecutionSpec;
import com.iwrite.llm.gateway.LlmInvalidResponseException;
import com.iwrite.observability.BusinessTelemetry;
import com.iwrite.scene.ai.SceneAnalysisPrompt;
import com.iwrite.scene.ai.WritingAssistant;
import com.iwrite.scene.dto.SceneAnalysisRequest;
import com.iwrite.scene.dto.SceneAnalysisResponse;
import com.iwrite.scene.entity.Scene;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

@Service
public class SceneAnalysisService {

    private static final String PROMPT_VERSION = "scene-analysis:v1";
    private static final int MAX_SCENE_TEXT_CHARS = 12_000;
    private static final int MAX_LIST_ITEMS = 5;

    private final SceneService sceneService;
    private final WritingAssistant writingAssistant;
    private final TipTapPlainTextRenderer tipTapPlainTextRenderer;
    private final TransactionTemplate readOnlyTransactionTemplate;
    private final LlmExecutionGateway llmExecutionGateway;
    private final BusinessTelemetry businessTelemetry;

    public SceneAnalysisService(
            SceneService sceneService,
            WritingAssistant writingAssistant,
            TipTapPlainTextRenderer tipTapPlainTextRenderer,
            PlatformTransactionManager transactionManager,
            LlmExecutionGateway llmExecutionGateway,
            BusinessTelemetry businessTelemetry
    ) {
        this.sceneService = sceneService;
        this.writingAssistant = writingAssistant;
        this.tipTapPlainTextRenderer = tipTapPlainTextRenderer;
        this.readOnlyTransactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate.setReadOnly(true);
        this.llmExecutionGateway = llmExecutionGateway;
        this.businessTelemetry = businessTelemetry;
    }

    /*
     * Telemetry wrapper only: the business body below is unchanged, and the
     * span is a child of whatever HTTP trace is active. See
     * docs/otel-business-signals.md.
     */
    public SceneAnalysisResponse analyze(UUID sceneId, SceneAnalysisRequest request) {
        try (BusinessTelemetry.Operation telemetry = businessTelemetry.sceneAnalysis()) {
            try {
                return analyze(sceneId, request, telemetry);
            } catch (BadRequestException validationError) {
                telemetry.failure(BusinessTelemetry.RESULT_VALIDATION_ERROR, validationError);
                throw validationError;
            } catch (ResourceNotFoundException notFound) {
                telemetry.failure(BusinessTelemetry.RESULT_NOT_FOUND, notFound);
                throw notFound;
            } catch (LlmExecutionException executionFailure) {
                telemetry.failure(telemetryResult(executionFailure), executionFailure);
                throw executionFailure;
            } catch (RuntimeException failure) {
                telemetry.failure(BusinessTelemetry.RESULT_FAILURE, failure);
                throw failure;
            }
        }
    }

    private SceneAnalysisResponse analyze(
            UUID sceneId,
            SceneAnalysisRequest request,
            BusinessTelemetry.Operation telemetry
    ) {
        telemetry.attribute(BusinessTelemetry.AI_FOCUS_PRESENT, usableFocus(request) != null);
        SceneAnalysisPrompt prompt = readOnlyTransactionTemplate.execute(status -> loadPrompt(sceneId, request));
        /*
         * Read after loadPrompt so a scene that fails validation still reaches
         * the assistant zero times, as SceneAnalysisAccessIntegrationTest requires.
         */
        telemetry.attribute(BusinessTelemetry.AI_PROVIDER, writingAssistant.provider());
        telemetry.attribute(BusinessTelemetry.AI_MODEL_FAMILY, BusinessTelemetry.modelFamily(writingAssistant.model()));
        telemetry.attribute(
                BusinessTelemetry.AI_INPUT_SIZE_BUCKET,
                BusinessTelemetry.modelInputSizeBucket(prompt.sceneText().length(), prompt.truncated())
        );
        LlmExecutionSpec spec = LlmExecutionSpec.of(
                        LlmFeature.SCENE_ANALYSIS,
                        writingAssistant.provider(),
                        writingAssistant.model(),
                        PROMPT_VERSION
                )
                .withResource(AuditResourceType.SCENE, sceneId);
        return llmExecutionGateway.execute(spec, context -> {
            LlmCallResult<SceneAnalysisResponse> result = sanitize(writingAssistant.analyzeScene(prompt));
            telemetry.attribute(BusinessTelemetry.AI_FALLBACK_USED, result.fallbackUsed());
            return result;
        });
    }

    /*
     * Stable error category -> controlled result token; the message never travels.
     *
     * FEATURE_DISABLED is a provider-side outcome, not an internal failure: the
     * assistant being switched off is the default deployment, so every ordinary
     * 503 would otherwise be classified as `failure` and — since the result now
     * also drives log severity — raise an ERROR event on a perfectly healthy
     * install. Only categories that mean this service or its configuration is
     * broken stay in `failure`.
     */
    private static String telemetryResult(LlmExecutionException failure) {
        return switch (failure.getErrorCategory()) {
            case INVALID_STRUCTURED_RESPONSE -> BusinessTelemetry.RESULT_INVALID_RESPONSE;
            case PROVIDER_TIMEOUT, PROVIDER_UNAVAILABLE, PROVIDER_REQUEST_REJECTED, FEATURE_DISABLED ->
                    BusinessTelemetry.RESULT_PROVIDER_ERROR;
            case CONFIGURATION_ERROR, AUDIT_PERSISTENCE_FAILURE, INTERNAL_EXECUTION_ERROR ->
                    BusinessTelemetry.RESULT_FAILURE;
        };
    }

    private SceneAnalysisPrompt loadPrompt(UUID sceneId, SceneAnalysisRequest request) {
        Scene scene = sceneService.getScene(sceneId);
        String sceneText = usableSceneText(scene);
        return new SceneAnalysisPrompt(
                truncateForModel(sceneText),
                usableFocus(request),
                sceneText.length() > MAX_SCENE_TEXT_CHARS
        );
    }

    private String usableSceneText(Scene scene) {
        String sceneText = tipTapPlainTextRenderer.render(scene.getContentJson())
                .orElseGet(() -> scene.getContentText() == null || scene.getContentText().isBlank()
                        ? null
                        : scene.getContentText());
        if (sceneText == null || sceneText.trim().isEmpty()) {
            throw new BadRequestException("Scene must contain textual content before AI analysis.");
        }
        return sceneText;
    }

    private String usableFocus(SceneAnalysisRequest request) {
        if (request == null || request.focus() == null || request.focus().trim().isEmpty()) {
            return null;
        }
        return request.focus().trim();
    }

    /*
     * The model receives the first 12,000 Java characters. This preserves the
     * scene opening and keeps long-scene handling deterministic and reviewable.
     */
    private String truncateForModel(String sceneText) {
        if (sceneText.length() <= MAX_SCENE_TEXT_CHARS) {
            return sceneText;
        }
        return sceneText.substring(0, MAX_SCENE_TEXT_CHARS);
    }

    private LlmCallResult<SceneAnalysisResponse> sanitize(LlmCallResult<SceneAnalysisResponse> result) {
        if (result == null || result.value() == null) {
            throw malformedResponse();
        }

        SceneAnalysisResponse response = result.value();
        SceneAnalysisResponse sanitized = new SceneAnalysisResponse(
                scalar(response.summary()),
                scalar(response.tone()),
                scalar(response.pacing()),
                list(response.strengths()),
                list(response.issues()),
                list(response.suggestions())
        );
        if (isEmpty(sanitized)) {
            throw malformedResponse();
        }
        return new LlmCallResult<>(
                sanitized,
                result.tokenUsage(),
                result.model(),
                result.fallbackUsed()
        );
    }

    private String scalar(String value) {
        return value == null ? "" : value.trim();
    }

    private List<String> list(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.trim().isEmpty())
                .map(String::trim)
                .limit(MAX_LIST_ITEMS)
                .toList();
    }

    private boolean isEmpty(SceneAnalysisResponse response) {
        return response.summary().isEmpty()
                && response.tone().isEmpty()
                && response.pacing().isEmpty()
                && response.strengths().isEmpty()
                && response.issues().isEmpty()
                && response.suggestions().isEmpty();
    }

    private LlmInvalidResponseException malformedResponse() {
        return new LlmInvalidResponseException(null);
    }
}
