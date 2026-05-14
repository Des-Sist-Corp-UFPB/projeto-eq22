package com.iwrite.item.dto;

import com.iwrite.character.dto.CharacterSummaryResponse;
import com.iwrite.character.entity.Character;
import com.iwrite.item.entity.Item;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ItemResponse(
        UUID id,
        UUID bookId,
        String name,
        String type,
        String description,
        String origin,
        UUID currentOwnerCharacterId,
        CharacterSummaryResponse currentOwnerCharacter,
        String narrativeImportance,
        String notes,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static ItemResponse fromEntity(Item item) {
        Character currentOwner = item.getCurrentOwnerCharacter();

        return new ItemResponse(
                item.getId(),
                item.getBook().getId(),
                item.getName(),
                item.getType(),
                item.getDescription(),
                item.getOrigin(),
                currentOwner == null ? null : currentOwner.getId(),
                CharacterSummaryResponse.fromEntity(currentOwner),
                item.getNarrativeImportance(),
                item.getNotes(),
                item.getCreatedAt(),
                item.getUpdatedAt()
        );
    }
}
