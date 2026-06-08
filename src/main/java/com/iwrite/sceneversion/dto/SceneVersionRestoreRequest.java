package com.iwrite.sceneversion.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record SceneVersionRestoreRequest(
        @NotNull Long expectedContentRevision,
        @NotNull UUID operationId
) {
    public SceneVersionRestoreRequest(Long expectedContentRevision) {
        this(expectedContentRevision, UUID.randomUUID());
    }
}
