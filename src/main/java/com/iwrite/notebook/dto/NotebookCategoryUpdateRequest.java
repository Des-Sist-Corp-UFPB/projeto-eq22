package com.iwrite.notebook.dto;

public record NotebookCategoryUpdateRequest(
        String name,
        Integer sortOrder
) {
}
