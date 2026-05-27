package com.iwrite.chapter.dto;

import com.iwrite.chapter.entity.Chapter;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ChapterResponse(
        UUID id,
        UUID bookId,
        UUID sectionId,
        String title,
        String summary,
        Integer sortOrder,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static ChapterResponse fromEntity(Chapter chapter) {
        return new ChapterResponse(
                chapter.getId(),
                chapter.getBook().getId(),
                chapter.getSection().getId(),
                chapter.getTitle(),
                chapter.getSummary(),
                chapter.getSortOrder(),
                chapter.getCreatedAt(),
                chapter.getUpdatedAt()
        );
    }
}
