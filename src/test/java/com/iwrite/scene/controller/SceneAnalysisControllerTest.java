package com.iwrite.scene.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iwrite.common.exception.GlobalExceptionHandler;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.common.exception.ServiceUnavailableException;
import com.iwrite.scene.dto.SceneAnalysisRequest;
import com.iwrite.scene.dto.SceneAnalysisResponse;
import com.iwrite.scene.service.SceneAnalysisService;
import com.iwrite.scene.service.SceneService;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SceneAnalysisControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SceneAnalysisService sceneAnalysisService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SceneService sceneService = mock(SceneService.class);
        sceneAnalysisService = mock(SceneAnalysisService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SceneController(sceneService, sceneAnalysisService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(new SpringValidatorAdapter(Validation.buildDefaultValidatorFactory().getValidator()))
                .build();
    }

    @Test
    void postAiAnalysisReturnsStructuredAnalysis() throws Exception {
        UUID sceneId = UUID.randomUUID();
        when(sceneAnalysisService.analyze(sceneId, new SceneAnalysisRequest("dialogue")))
                .thenReturn(new SceneAnalysisResponse(
                        "Focused exchange.",
                        "Warm",
                        "Quick",
                        List.of("Distinct voices"),
                        List.of("Abrupt ending"),
                        List.of("Let the last beat land")
                ));

        mockMvc.perform(post("/api/scenes/{sceneId}/ai-analysis", sceneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SceneAnalysisRequest("dialogue"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("Focused exchange."))
                .andExpect(jsonPath("$.tone").value("Warm"))
                .andExpect(jsonPath("$.pacing").value("Quick"))
                .andExpect(jsonPath("$.strengths[0]").value("Distinct voices"))
                .andExpect(jsonPath("$.issues[0]").value("Abrupt ending"))
                .andExpect(jsonPath("$.suggestions[0]").value("Let the last beat land"));
    }

    @Test
    void postAiAnalysisAcceptsMissingBody() throws Exception {
        UUID sceneId = UUID.randomUUID();
        when(sceneAnalysisService.analyze(sceneId, null)).thenReturn(new SceneAnalysisResponse(
                "Summary",
                "Tone",
                "Pacing",
                List.of(),
                List.of(),
                List.of()
        ));

        mockMvc.perform(post("/api/scenes/{sceneId}/ai-analysis", sceneId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("Summary"));
    }

    @Test
    void postAiAnalysisReturnsServiceUnavailableWhenAiDisabled() throws Exception {
        UUID sceneId = UUID.randomUUID();
        when(sceneAnalysisService.analyze(sceneId, null))
                .thenThrow(new ServiceUnavailableException("AI scene analysis is not available."));

        mockMvc.perform(post("/api/scenes/{sceneId}/ai-analysis", sceneId))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.messages", hasItem("AI scene analysis is not available.")));
    }

    @Test
    void postAiAnalysisReturnsNotFoundForMissingScene() throws Exception {
        UUID sceneId = UUID.randomUUID();
        when(sceneAnalysisService.analyze(sceneId, null))
                .thenThrow(new ResourceNotFoundException("Scene not found: " + sceneId));

        mockMvc.perform(post("/api/scenes/{sceneId}/ai-analysis", sceneId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messages", hasItem(containsString("Scene not found"))));
    }

    @Test
    void postAiAnalysisRejectsFocusLongerThan300Characters() throws Exception {
        UUID sceneId = UUID.randomUUID();

        mockMvc.perform(post("/api/scenes/{sceneId}/ai-analysis", sceneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SceneAnalysisRequest("x".repeat(301)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages", hasItem(containsString("focus"))));
    }
}
