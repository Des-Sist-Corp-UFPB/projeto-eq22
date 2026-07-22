package com.iwrite.notebook.dto;

import jakarta.validation.constraints.NotBlank;

public record NotebookCategoryRequest(
        @NotBlank String name,
        Integer sortOrder
) {
}
