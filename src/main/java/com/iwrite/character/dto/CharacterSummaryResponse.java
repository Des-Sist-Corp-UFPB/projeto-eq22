package com.iwrite.character.dto;

import com.iwrite.character.entity.Character;

import java.util.UUID;

public record CharacterSummaryResponse(
        UUID id,
        String name,
        String nickname
) {

    public static CharacterSummaryResponse fromEntity(Character character) {
        if (character == null) {
            return null;
        }

        return new CharacterSummaryResponse(
                character.getId(),
                character.getName(),
                character.getNickname()
        );
    }
}
