package com.iwrite.item.dto;

import java.util.UUID;

public record ItemUpdateRequest(
        String name,
        String type,
        String description,
        String origin,
        UUID currentOwnerCharacterId,
        String narrativeImportance,
        String notes
) {
}
