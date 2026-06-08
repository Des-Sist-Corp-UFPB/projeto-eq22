package com.iwrite.sceneversion.dto;

import jakarta.validation.constraints.NotNull;

public record SceneVersionRestoreRequest(
        @NotNull Long expectedContentRevision
) {
}
