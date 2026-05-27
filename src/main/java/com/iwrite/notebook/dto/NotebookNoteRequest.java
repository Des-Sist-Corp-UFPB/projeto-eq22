package com.iwrite.notebook.dto;

import com.iwrite.notebook.entity.NotebookNoteStatus;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record NotebookNoteRequest(
        @NotBlank String title,
        String content,
        UUID categoryId,
        NotebookNoteStatus status
) {
}
