package com.iwrite.scene.dto;

import com.iwrite.scene.entity.Scene;
import com.iwrite.scene.entity.SceneStatus;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public record SceneResponse(
        UUID id,
        UUID bookId,
        UUID chapterId,
        String title,
        String summary,
        String contentJson,
        String contentText,
        SceneStatus status,
        Integer sortOrder,
        Integer wordCount,
        String goal,
        String conflict,
        String outcome,
        CharacterSummaryResponse povCharacter,
        LocationSummaryResponse mainLocation,
        List<CharacterSummaryResponse> participantCharacters,
        List<ItemSummaryResponse> items,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static SceneResponse fromEntity(Scene scene) {
        return new SceneResponse(
                scene.getId(),
                scene.getBook().getId(),
                scene.getChapter().getId(),
                scene.getTitle(),
                scene.getSummary(),
                scene.getContentJson(),
                scene.getContentText(),
                scene.getStatus(),
                scene.getSortOrder(),
                scene.getWordCount(),
                scene.getGoal(),
                scene.getConflict(),
                scene.getOutcome(),
                CharacterSummaryResponse.fromEntity(scene.getPovCharacter()),
                LocationSummaryResponse.fromEntity(scene.getMainLocation()),
                scene.getParticipantCharacters()
                        .stream()
                        .map(CharacterSummaryResponse::fromEntity)
                        .sorted(Comparator.comparing(CharacterSummaryResponse::name).thenComparing(CharacterSummaryResponse::id))
                        .toList(),
                scene.getItems()
                        .stream()
                        .map(ItemSummaryResponse::fromEntity)
                        .sorted(Comparator.comparing(ItemSummaryResponse::name).thenComparing(ItemSummaryResponse::id))
                        .toList(),
                scene.getCreatedAt(),
                scene.getUpdatedAt()
        );
    }
}
