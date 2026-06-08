package com.iwrite.scene.dto;

import com.iwrite.sceneversion.entity.SceneVersionSource;
import jakarta.validation.constraints.NotNull;

public record SceneContentRequest(
        String contentJson,
        String contentText,
        SceneVersionSource source,
        @NotNull Long expectedContentRevision
) {

    public SceneContentRequest(String contentJson, String contentText) {
        this(contentJson, contentText, SceneVersionSource.AUTO_SAVE, 0L);
    }
}
