package com.iwrite.book.dto;

import com.iwrite.book.entity.BookCollaborator;

import java.time.OffsetDateTime;
import java.util.UUID;

public record BookCollaboratorResponse(
        UUID userId,
        String displayName,
        OffsetDateTime createdAt
) {

    public static BookCollaboratorResponse fromEntity(BookCollaborator collaborator) {
        return new BookCollaboratorResponse(
                collaborator.getUser().getId(),
                collaborator.getUser().getDisplayName(),
                collaborator.getCreatedAt()
        );
    }
}
