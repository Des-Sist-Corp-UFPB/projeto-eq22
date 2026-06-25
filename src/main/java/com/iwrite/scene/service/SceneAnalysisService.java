package com.iwrite.scene.service;

import com.iwrite.common.exception.BadRequestException;
import com.iwrite.common.exception.ServiceUnavailableException;
import com.iwrite.scene.ai.SceneAnalysisPrompt;
import com.iwrite.scene.ai.WritingAssistant;
import com.iwrite.scene.dto.SceneAnalysisRequest;
import com.iwrite.scene.dto.SceneAnalysisResponse;
import com.iwrite.scene.entity.Scene;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class SceneAnalysisService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SceneAnalysisService.class);
    private static final int MAX_SCENE_TEXT_CHARS = 12_000;
    private static final int MAX_LIST_ITEMS = 5;

    private final SceneService sceneService;
    private final WritingAssistant writingAssistant;

    public SceneAnalysisService(SceneService sceneService, WritingAssistant writingAssistant) {
        this.sceneService = sceneService;
        this.writingAssistant = writingAssistant;
    }

    @Transactional(readOnly = true)
    public SceneAnalysisResponse analyze(UUID sceneId, SceneAnalysisRequest request) {
        Scene scene = sceneService.getScene(sceneId);
        String sceneText = usableSceneText(scene);
        String focus = usableFocus(request);
        SceneAnalysisPrompt prompt = new SceneAnalysisPrompt(
                truncateForModel(sceneText),
                focus,
                sceneText.length() > MAX_SCENE_TEXT_CHARS
        );

        long startedAt = System.nanoTime();
        try {
            SceneAnalysisResponse response = sanitize(writingAssistant.analyzeScene(prompt));
            logResult(sceneId, startedAt, "success", "none");
            return response;
        } catch (ServiceUnavailableException exception) {
            logResult(sceneId, startedAt, "failure", "assistant_unavailable");
            throw exception;
        } catch (RuntimeException exception) {
            logResult(sceneId, startedAt, "failure", "assistant_failure");
            throw new ServiceUnavailableException("AI scene analysis could not be completed.", exception);
        }
    }

    private String usableSceneText(Scene scene) {
        String contentText = scene.getContentText();
        if (contentText == null || contentText.trim().isEmpty()) {
            throw new BadRequestException("Scene must contain textual content before AI analysis.");
        }
        return contentText;
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

    private SceneAnalysisResponse sanitize(SceneAnalysisResponse response) {
        if (response == null) {
            throw malformedResponse();
        }

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
        return sanitized;
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

    private ServiceUnavailableException malformedResponse() {
        return new ServiceUnavailableException("AI scene analysis returned an invalid response. Please try again later.");
    }

    private void logResult(UUID sceneId, long startedAt, String outcome, String category) {
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        LOGGER.info(
                "AI scene analysis sceneId={} elapsedMs={} outcome={} category={}",
                sceneId,
                elapsedMs,
                outcome,
                category
        );
    }
}
