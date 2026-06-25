package com.iwrite.scene.service;

import com.iwrite.common.exception.BadRequestException;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.common.exception.ServiceUnavailableException;
import com.iwrite.scene.ai.SceneAnalysisPrompt;
import com.iwrite.scene.ai.WritingAssistant;
import com.iwrite.scene.dto.SceneAnalysisRequest;
import com.iwrite.scene.dto.SceneAnalysisResponse;
import com.iwrite.scene.entity.Scene;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SceneAnalysisServiceTest {

    @Mock
    private SceneService sceneService;

    @Mock
    private WritingAssistant writingAssistant;

    @InjectMocks
    private SceneAnalysisService service;

    @Test
    void analyzesSceneWithStructuredResponse() {
        UUID sceneId = UUID.randomUUID();
        Scene scene = sceneWithText("The door opened. The room held its breath.");
        when(sceneService.getScene(sceneId)).thenReturn(scene);
        when(writingAssistant.analyzeScene(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new SceneAnalysisResponse(
                        "A tense entrance.",
                        "Suspenseful",
                        "Measured",
                        List.of("Clear mood"),
                        List.of("Conflict is still implied"),
                        List.of("Clarify what is at stake")
                ));

        SceneAnalysisResponse response = service.analyze(
                sceneId,
                new SceneAnalysisRequest(" pacing ")
        );

        assertThat(response.summary()).isEqualTo("A tense entrance.");
        assertThat(response.strengths()).containsExactly("Clear mood");
        ArgumentCaptor<SceneAnalysisPrompt> prompt = ArgumentCaptor.forClass(SceneAnalysisPrompt.class);
        verify(writingAssistant).analyzeScene(prompt.capture());
        assertThat(prompt.getValue().sceneText()).isEqualTo(scene.getContentText());
        assertThat(prompt.getValue().focus()).isEqualTo("pacing");
        assertThat(prompt.getValue().truncated()).isFalse();
    }

    @Test
    void rejectsMissingSceneThroughExistingAccessFlow() {
        UUID sceneId = UUID.randomUUID();
        when(sceneService.getScene(sceneId)).thenThrow(new ResourceNotFoundException("Scene not found: " + sceneId));

        assertThatThrownBy(() -> service.analyze(sceneId, null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Scene not found");
    }

    @Test
    void rejectsBlankSceneContent() {
        UUID sceneId = UUID.randomUUID();
        when(sceneService.getScene(sceneId)).thenReturn(sceneWithText(""));

        assertThatThrownBy(() -> service.analyze(sceneId, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("textual content");
    }

    @Test
    void rejectsWhitespaceOnlySceneContent() {
        UUID sceneId = UUID.randomUUID();
        when(sceneService.getScene(sceneId)).thenReturn(sceneWithText("   \n\t  "));

        assertThatThrownBy(() -> service.analyze(sceneId, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("textual content");
    }

    @Test
    void truncatesLongSceneTextDeterministicallyAndDoesNotModifyScene() {
        UUID sceneId = UUID.randomUUID();
        String originalText = "a".repeat(12_050);
        Scene scene = sceneWithText(originalText);
        when(sceneService.getScene(sceneId)).thenReturn(scene);
        when(writingAssistant.analyzeScene(org.mockito.ArgumentMatchers.any()))
                .thenReturn(validAnalysis());

        service.analyze(sceneId, null);

        ArgumentCaptor<SceneAnalysisPrompt> prompt = ArgumentCaptor.forClass(SceneAnalysisPrompt.class);
        verify(writingAssistant).analyzeScene(prompt.capture());
        assertThat(prompt.getValue().sceneText()).hasSize(12_000);
        assertThat(prompt.getValue().sceneText()).isEqualTo(originalText.substring(0, 12_000));
        assertThat(prompt.getValue().truncated()).isTrue();
        assertThat(scene.getContentText()).isEqualTo(originalText);
    }

    @Test
    void normalizesNullFieldsAndLimitsLists() {
        UUID sceneId = UUID.randomUUID();
        when(sceneService.getScene(sceneId)).thenReturn(sceneWithText("Some scene words."));
        when(writingAssistant.analyzeScene(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new SceneAnalysisResponse(
                        null,
                        "  gentle ",
                        null,
                        null,
                        Arrays.asList(" one ", null, " ", "two", "three", "four", "five", "six"),
                        List.of("try a sharper turn")
                ));

        SceneAnalysisResponse response = service.analyze(sceneId, null);

        assertThat(response.summary()).isEmpty();
        assertThat(response.tone()).isEqualTo("gentle");
        assertThat(response.strengths()).isEmpty();
        assertThat(response.issues()).containsExactly("one", "two", "three", "four", "five");
    }

    @Test
    void rejectsInvalidStructuredOutput() {
        UUID sceneId = UUID.randomUUID();
        when(sceneService.getScene(sceneId)).thenReturn(sceneWithText("Some scene words."));
        when(writingAssistant.analyzeScene(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new SceneAnalysisResponse(null, null, null, null, null, null));

        assertThatThrownBy(() -> service.analyze(sceneId, null))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("invalid response");
    }

    @Test
    void mapsAssistantRuntimeFailureToSafeUnavailableError() {
        UUID sceneId = UUID.randomUUID();
        when(sceneService.getScene(sceneId)).thenReturn(sceneWithText("Some scene words."));
        when(writingAssistant.analyzeScene(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("raw upstream body"));

        assertThatThrownBy(() -> service.analyze(sceneId, null))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessage("AI scene analysis could not be completed.");
    }

    private Scene sceneWithText(String contentText) {
        Scene scene = new Scene();
        scene.setContentText(contentText);
        scene.setContentJson("{\"type\":\"doc\"}");
        return scene;
    }

    private SceneAnalysisResponse validAnalysis() {
        return new SceneAnalysisResponse(
                "Summary",
                "Tone",
                "Pacing",
                List.of("Strength"),
                List.of("Issue"),
                List.of("Suggestion")
        );
    }
}
