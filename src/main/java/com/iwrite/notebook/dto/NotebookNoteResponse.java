package com.iwrite.notebook.dto;

import com.iwrite.notebook.entity.NotebookCategory;
import com.iwrite.notebook.entity.NotebookNote;

import java.time.OffsetDateTime;
import java.util.UUID;

public record NotebookNoteResponse(
        UUID id,
        UUID bookId,
        UUID categoryId,
        NotebookCategoryResponse category,
        String title,
        String content,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static NotebookNoteResponse fromEntity(NotebookNote note) {
        NotebookCategory category = note.getCategory();
        return new NotebookNoteResponse(
                note.getId(),
                note.getBook().getId(),
                category == null ? null : category.getId(),
                category == null ? null : NotebookCategoryResponse.fromEntity(category),
                note.getTitle(),
                note.getContent(),
                note.getCreatedAt(),
                note.getUpdatedAt()
        );
    }
}
