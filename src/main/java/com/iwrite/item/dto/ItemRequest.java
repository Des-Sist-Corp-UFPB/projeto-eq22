package com.iwrite.item.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record ItemRequest(
        @NotBlank String name,
        String type,
        String description,
        String origin,
        UUID currentOwnerCharacterId,
        String narrativeImportance,
        String notes
) {
}
