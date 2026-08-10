package com.iwrite.outline.dto;

import com.iwrite.section.entity.SectionType;

import java.util.List;
import java.util.UUID;

public record OutlineSectionResponse(
        UUID id,
        String title,
        SectionType type,
        Integer sortOrder,
        Integer wordCount,
        List<OutlineChapterResponse> chapters
) {
}
