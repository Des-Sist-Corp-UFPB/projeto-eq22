package com.iwrite.chapter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

public record ChapterRequest(
        @NotBlank String title,
        String summary,
        @PositiveOrZero Integer sortOrder
) {
}
