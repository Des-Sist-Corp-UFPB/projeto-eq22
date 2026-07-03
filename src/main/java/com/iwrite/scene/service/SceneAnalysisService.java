package com.iwrite.scene.service;

import com.iwrite.common.exception.BadRequestException;
import com.iwrite.export.service.TipTapPlainTextRenderer;
import com.iwrite.audit.entity.AuditResourceType;
import com.iwrite.llm.LlmFeature;
import com.iwrite.llm.gateway.LlmCallResult;
import com.iwrite.llm.gateway.LlmExecutionGateway;
import com.iwrite.llm.gateway.LlmExecutionSpec;
import com.iwrite.llm.gateway.LlmInvalidResponseException;
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

    public SceneAnalysisService(
            SceneService sceneService,
            WritingAssistant writingAssistant,
            TipTapPlainTextRenderer tipTapPlainTextRenderer,
            PlatformTransactionManager transactionManager,
            LlmExecutionGateway llmExecutionGateway
    ) {
        this.sceneService = sceneService;
        this.writingAssistant = writingAssistant;
        this.tipTapPlainTextRenderer = tipTapPlainTextRenderer;
        this.readOnlyTransactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate.setReadOnly(true);
        this.llmExecutionGateway = llmExecutionGateway;
    }

    public SceneAnalysisResponse analyze(UUID sceneId, SceneAnalysisRequest request) {
        SceneAnalysisPrompt prompt = readOnlyTransactionTemplate.execute(status -> loadPrompt(sceneId, request));
        LlmExecutionSpec spec = LlmExecutionSpec.of(
                        LlmFeature.SCENE_ANALYSIS,
                        writingAssistant.provider(),
                        writingAssistant.model(),
                        PROMPT_VERSION
                )
                .withResource(AuditResourceType.SCENE, sceneId);
        return llmExecutionGateway.execute(spec, context -> sanitize(writingAssistant.analyzeScene(prompt)));
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
