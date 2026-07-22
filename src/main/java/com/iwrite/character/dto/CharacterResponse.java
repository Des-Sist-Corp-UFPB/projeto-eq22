package com.iwrite.character.dto;

import com.iwrite.character.entity.Character;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CharacterResponse(
        UUID id,
        UUID bookId,
        String name,
        String nickname,
        Integer age,
        String sex,
        String narrativeFunction,
        String goal,
        String conflict,
        String arc,
        String physicalDescription,
        String personality,
        String biography,
        String notes,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static CharacterResponse fromEntity(Character character) {
        return new CharacterResponse(
                character.getId(),
                character.getBook().getId(),
                character.getName(),
                character.getNickname(),
                character.getAge(),
                character.getSex(),
                character.getNarrativeFunction(),
                character.getGoal(),
                character.getConflict(),
                character.getArc(),
                character.getPhysicalDescription(),
                character.getPersonality(),
                character.getBiography(),
                character.getNotes(),
                character.getCreatedAt(),
                character.getUpdatedAt()
        );
    }
}
