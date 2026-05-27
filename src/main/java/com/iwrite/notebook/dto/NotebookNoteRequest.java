package com.iwrite.notebook.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record NotebookNoteRequest(
        @NotBlank String title,
        String content,
        UUID categoryId
) {
}
