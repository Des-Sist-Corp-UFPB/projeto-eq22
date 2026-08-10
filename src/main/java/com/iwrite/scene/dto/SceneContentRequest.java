package com.iwrite.scene.dto;

import com.iwrite.sceneversion.entity.SceneVersionSource;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record SceneContentRequest(
        String contentJson,
        String contentText,
        SceneVersionSource source,
        @NotNull Long expectedContentRevision,
        @NotNull UUID operationId
) {

    public SceneContentRequest(String contentJson, String contentText) {
        this(contentJson, contentText, SceneVersionSource.AUTO_SAVE, 0L, UUID.randomUUID());
    }

    public SceneContentRequest(String contentJson, String contentText, SceneVersionSource source, Long expectedContentRevision) {
        this(contentJson, contentText, source, expectedContentRevision, UUID.randomUUID());
    }
}
