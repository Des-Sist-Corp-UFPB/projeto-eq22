package com.iwrite.sceneversion.controller;

import com.iwrite.scene.dto.SceneResponse;
import com.iwrite.scene.service.SceneService;
import com.iwrite.sceneversion.dto.SceneVersionDetailResponse;
import com.iwrite.sceneversion.dto.SceneVersionPageResponse;
import com.iwrite.sceneversion.dto.SceneVersionRestoreRequest;
import com.iwrite.sceneversion.service.SceneVersionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api")
public class SceneVersionController {

    private final SceneVersionService sceneVersionService;
    private final SceneService sceneService;

    public SceneVersionController(SceneVersionService sceneVersionService, SceneService sceneService) {
        this.sceneVersionService = sceneVersionService;
        this.sceneService = sceneService;
    }

    @GetMapping("/scenes/{sceneId}/versions")
    public SceneVersionPageResponse listVersions(
            @PathVariable UUID sceneId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return sceneVersionService.listVersions(sceneId, page, size);
    }

    @GetMapping("/scenes/{sceneId}/versions/{versionId}")
    public SceneVersionDetailResponse findVersion(@PathVariable UUID sceneId, @PathVariable UUID versionId) {
        return sceneVersionService.findVersion(sceneId, versionId);
    }

    @PostMapping("/scenes/{sceneId}/versions/{versionId}/restore")
    public SceneResponse restoreVersion(
            @PathVariable UUID sceneId,
            @PathVariable UUID versionId,
            @Valid @RequestBody SceneVersionRestoreRequest request
    ) {
        return sceneService.restoreVersion(sceneId, versionId, request);
    }
}
