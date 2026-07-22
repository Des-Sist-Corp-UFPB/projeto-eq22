package com.iwrite.section.dto;

import com.iwrite.section.entity.SectionType;
import jakarta.validation.constraints.PositiveOrZero;

public record BookSectionUpdateRequest(
        String title,
        SectionType type,
        @PositiveOrZero Integer sortOrder
) {
}
