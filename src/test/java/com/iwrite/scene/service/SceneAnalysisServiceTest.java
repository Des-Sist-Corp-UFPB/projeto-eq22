package com.iwrite.scene.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iwrite.common.exception.BadRequestException;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.common.exception.ServiceUnavailableException;
import com.iwrite.export.service.TipTapPlainTextRenderer;
import com.iwrite.scene.ai.SceneAnalysisPrompt;
import com.iwrite.scene.ai.WritingAssistant;
import com.iwrite.scene.dto.SceneAnalysisRequest;
import com.iwrite.scene.dto.SceneAnalysisResponse;
import com.iwrite.scene.entity.Scene;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SceneAnalysisServiceTest {

    @Mock
    private SceneService sceneService;

    @Mock
    private WritingAssistant writingAssistant;

    private SceneAnalysisService service;

    @BeforeEach
    void setUp() {
        service = new SceneAnalysisService(
                sceneService,
                writingAssistant,
                new TipTapPlainTextRenderer(new ObjectMapper()),
                new TestTransactionManager()
        );
    }

    @Test
    void analyzesSceneWithStructuredResponse() {
        UUID sceneId = UUID.randomUUID();
        Scene scene = sceneWithText("The door opened. The room held its breath.");
        when(sceneService.getScene(sceneId)).thenReturn(scene);
        when(writingAssistant.analyzeScene(any()))
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
        when(writingAssistant.analyzeScene(any())).thenReturn(validAnalysis());

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
        when(writingAssistant.analyzeScene(any()))
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
    void usesRenderedContentJsonWhenContentTextIsBlank() {
        UUID sceneId = UUID.randomUUID();
        when(sceneService.getScene(sceneId)).thenReturn(sceneWithContentJson(paragraphJson("Rendered current words."), ""));
        when(writingAssistant.analyzeScene(any())).thenReturn(validAnalysis());

        service.analyze(sceneId, null);

        ArgumentCaptor<SceneAnalysisPrompt> prompt = ArgumentCaptor.forClass(SceneAnalysisPrompt.class);
        verify(writingAssistant).analyzeScene(prompt.capture());
        assertThat(prompt.getValue().sceneText()).isEqualTo("Rendered current words.");
    }

    @Test
    void usesRenderedContentJsonInsteadOfStaleConflictingContentText() {
        UUID sceneId = UUID.randomUUID();
        when(sceneService.getScene(sceneId)).thenReturn(sceneWithContentJson(
                paragraphJson("Current JSON words."),
                "stale fallback words"
        ));
        when(writingAssistant.analyzeScene(any())).thenReturn(validAnalysis());

        service.analyze(sceneId, null);

        ArgumentCaptor<SceneAnalysisPrompt> prompt = ArgumentCaptor.forClass(SceneAnalysisPrompt.class);
        verify(writingAssistant).analyzeScene(prompt.capture());
        assertThat(prompt.getValue().sceneText()).isEqualTo("Current JSON words.");
    }

    @Test
    void fallsBackToContentTextWhenContentJsonIsAbsent() {
        UUID sceneId = UUID.randomUUID();
        when(sceneService.getScene(sceneId)).thenReturn(sceneWithContentJson(null, "Fallback from text."));
        when(writingAssistant.analyzeScene(any())).thenReturn(validAnalysis());

        service.analyze(sceneId, null);

        ArgumentCaptor<SceneAnalysisPrompt> prompt = ArgumentCaptor.forClass(SceneAnalysisPrompt.class);
        verify(writingAssistant).analyzeScene(prompt.capture());
        assertThat(prompt.getValue().sceneText()).isEqualTo("Fallback from text.");
    }

    @Test
    void fallsBackToContentTextWhenContentJsonCannotBeRendered() {
        UUID sceneId = UUID.randomUUID();
        when(sceneService.getScene(sceneId)).thenReturn(sceneWithContentJson("{not-json", "Fallback from invalid JSON."));
        when(writingAssistant.analyzeScene(any())).thenReturn(validAnalysis());

        service.analyze(sceneId, null);

        ArgumentCaptor<SceneAnalysisPrompt> prompt = ArgumentCaptor.forClass(SceneAnalysisPrompt.class);
        verify(writingAssistant).analyzeScene(prompt.capture());
        assertThat(prompt.getValue().sceneText()).isEqualTo("Fallback from invalid JSON.");
    }

    @Test
    void rejectsSceneWhenContentJsonAndContentTextAreBothEmpty() {
        UUID sceneId = UUID.randomUUID();
        when(sceneService.getScene(sceneId)).thenReturn(sceneWithContentJson(paragraphJson(""), "   "));

        assertThatThrownBy(() -> service.analyze(sceneId, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("textual content");
    }

    @Test
    void invokesWritingAssistantAfterReadTransactionCompletes() {
        UUID sceneId = UUID.randomUUID();
        when(sceneService.getScene(sceneId)).thenReturn(sceneWithText("Some scene words."));
        when(writingAssistant.analyzeScene(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return validAnalysis();
        });

        service.analyze(sceneId, null);
    }

    @Test
    void rejectsInvalidStructuredOutput() {
        UUID sceneId = UUID.randomUUID();
        when(sceneService.getScene(sceneId)).thenReturn(sceneWithText("Some scene words."));
        when(writingAssistant.analyzeScene(any()))
                .thenReturn(new SceneAnalysisResponse(null, null, null, null, null, null));

        assertThatThrownBy(() -> service.analyze(sceneId, null))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("invalid response");
    }

    @Test
    void mapsAssistantRuntimeFailureToSafeUnavailableError() {
        UUID sceneId = UUID.randomUUID();
        when(sceneService.getScene(sceneId)).thenReturn(sceneWithText("Some scene words."));
        when(writingAssistant.analyzeScene(any()))
                .thenThrow(new IllegalStateException("raw upstream body"));

        assertThatThrownBy(() -> service.analyze(sceneId, null))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessage("AI scene analysis could not be completed.");
    }

    private Scene sceneWithText(String contentText) {
        return sceneWithContentJson("{\"type\":\"doc\"}", contentText);
    }

    private Scene sceneWithContentJson(String contentJson, String contentText) {
        Scene scene = new Scene();
        scene.setContentText(contentText);
        scene.setContentJson(contentJson);
        return scene;
    }

    private String paragraphJson(String text) {
        return """
                {"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"%s"}]}]}
                """.formatted(text);
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

    private static class TestTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
