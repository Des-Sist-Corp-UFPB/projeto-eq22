package com.iwrite.location.dto;

import com.iwrite.location.entity.Location;

import java.time.OffsetDateTime;
import java.util.UUID;

public record LocationResponse(
        UUID id,
        UUID bookId,
        String name,
        String type,
        String description,
        String historyContext,
        String narrativeImportance,
        String notes,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static LocationResponse fromEntity(Location location) {
        return new LocationResponse(
                location.getId(),
                location.getBook().getId(),
                location.getName(),
                location.getType(),
                location.getDescription(),
                location.getHistoryContext(),
                location.getNarrativeImportance(),
                location.getNotes(),
                location.getCreatedAt(),
                location.getUpdatedAt()
        );
    }
}
