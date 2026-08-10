package com.iwrite.chapter.dto;

import jakarta.validation.constraints.PositiveOrZero;

public record ChapterUpdateRequest(
        String title,
        String summary,
        @PositiveOrZero Integer sortOrder
) {
}
