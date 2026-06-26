package com.iwrite.scene.controller;

import com.iwrite.common.dto.ReorderRequest;
import com.iwrite.scene.dto.SceneAnalysisRequest;
import com.iwrite.scene.dto.SceneAnalysisResponse;
import com.iwrite.scene.dto.SceneContentRequest;
import com.iwrite.scene.dto.ScenePlanningRequest;
import com.iwrite.scene.dto.SceneRequest;
import com.iwrite.scene.dto.SceneResponse;
import com.iwrite.scene.dto.SceneUpdateRequest;
import com.iwrite.scene.service.SceneAnalysisService;
import com.iwrite.scene.service.SceneService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api")
public class SceneController {

    private final SceneService sceneService;
    private final SceneAnalysisService sceneAnalysisService;

    public SceneController(SceneService sceneService, SceneAnalysisService sceneAnalysisService) {
        this.sceneService = sceneService;
        this.sceneAnalysisService = sceneAnalysisService;
    }

    @PostMapping("/chapters/{chapterId}/scenes")
    @ResponseStatus(HttpStatus.CREATED)
    public SceneResponse create(@PathVariable UUID chapterId, @Valid @RequestBody SceneRequest request) {
        return sceneService.create(chapterId, request);
    }

    @GetMapping("/scenes/{sceneId}")
    public SceneResponse findById(@PathVariable UUID sceneId) {
        return sceneService.findById(sceneId);
    }

    @PatchMapping("/scenes/{sceneId}")
    public SceneResponse update(@PathVariable UUID sceneId, @Valid @RequestBody SceneUpdateRequest request) {
        return sceneService.update(sceneId, request);
    }

    @PatchMapping("/scenes/{sceneId}/content")
    public SceneResponse updateContent(@PathVariable UUID sceneId, @Valid @RequestBody SceneContentRequest request) {
        return sceneService.updateContent(sceneId, request);
    }

    @PatchMapping("/scenes/{sceneId}/planning")
    public SceneResponse updatePlanning(@PathVariable UUID sceneId, @Valid @RequestBody ScenePlanningRequest request) {
        return sceneService.updatePlanning(sceneId, request);
    }

    @PostMapping("/scenes/{sceneId}/ai-analysis")
    public SceneAnalysisResponse analyze(
            @PathVariable UUID sceneId,
            @Valid @RequestBody(required = false) SceneAnalysisRequest request
    ) {
        return sceneAnalysisService.analyze(sceneId, request);
    }

    @PatchMapping("/chapters/{chapterId}/scenes/reorder")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reorder(@PathVariable UUID chapterId, @Valid @RequestBody ReorderRequest request) {
        sceneService.reorder(chapterId, request);
    }

    @DeleteMapping("/scenes/{sceneId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID sceneId) {
        sceneService.delete(sceneId);
    }
}
