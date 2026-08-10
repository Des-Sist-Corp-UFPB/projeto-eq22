package com.iwrite.notebook.dto;

import com.iwrite.notebook.entity.NotebookCategory;
import com.iwrite.notebook.entity.NotebookNote;
import com.iwrite.notebook.entity.NotebookNoteStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record NotebookNoteResponse(
        UUID id,
        UUID bookId,
        UUID categoryId,
        NotebookCategoryResponse category,
        String title,
        String content,
        NotebookNoteStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static NotebookNoteResponse fromEntity(NotebookNote note) {
        NotebookCategory category = sameBookCategory(note);
        return new NotebookNoteResponse(
                note.getId(),
                note.getBook().getId(),
                category == null ? null : category.getId(),
                category == null ? null : NotebookCategoryResponse.fromEntity(category),
                note.getTitle(),
                note.getContent(),
                note.getStatus(),
                note.getCreatedAt(),
                note.getUpdatedAt()
        );
    }

    private static NotebookCategory sameBookCategory(NotebookNote note) {
        NotebookCategory category = note.getCategory();
        if (category == null) {
            return null;
        }

        UUID noteBookId = note.getBook().getId();
        UUID categoryBookId = category.getBook().getId();
        return noteBookId.equals(categoryBookId) ? category : null;
    }
}
