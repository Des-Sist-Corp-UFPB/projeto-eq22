package com.iwrite.section.dto;

import com.iwrite.section.entity.BookSection;
import com.iwrite.section.entity.SectionType;

import java.time.OffsetDateTime;
import java.util.UUID;

public record BookSectionResponse(
        UUID id,
        UUID bookId,
        String title,
        SectionType type,
        Integer sortOrder,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static BookSectionResponse fromEntity(BookSection section) {
        return new BookSectionResponse(
                section.getId(),
                section.getBook().getId(),
                section.getTitle(),
                section.getType(),
                section.getSortOrder(),
                section.getCreatedAt(),
                section.getUpdatedAt()
        );
    }
}
