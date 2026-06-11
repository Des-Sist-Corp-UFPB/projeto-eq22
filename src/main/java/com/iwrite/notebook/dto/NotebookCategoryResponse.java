package com.iwrite.notebook.dto;

import com.iwrite.notebook.entity.NotebookCategory;

import java.time.OffsetDateTime;
import java.util.UUID;

public record NotebookCategoryResponse(
        UUID id,
        UUID bookId,
        String name,
        Integer sortOrder,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static NotebookCategoryResponse fromEntity(NotebookCategory category) {
        return new NotebookCategoryResponse(
                category.getId(),
                category.getBook().getId(),
                category.getName(),
                category.getSortOrder(),
                category.getCreatedAt(),
                category.getUpdatedAt()
        );
    }
}
