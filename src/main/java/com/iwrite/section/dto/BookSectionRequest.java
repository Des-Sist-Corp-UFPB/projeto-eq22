package com.iwrite.section.dto;

import com.iwrite.section.entity.SectionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

public record BookSectionRequest(
        @NotBlank String title,
        SectionType type,
        @PositiveOrZero Integer sortOrder
) {
}
