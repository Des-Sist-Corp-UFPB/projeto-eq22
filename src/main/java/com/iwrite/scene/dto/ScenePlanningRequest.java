package com.iwrite.scene.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record ScenePlanningRequest(
        String goal,
        String conflict,
        String outcome,
        UUID povCharacterId,
        @NotNull List<UUID> participantCharacterIds,
        UUID mainLocationId,
        @NotNull List<UUID> itemIds
) {
}
