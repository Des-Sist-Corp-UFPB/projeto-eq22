package com.iwrite.outline.dto;

import com.iwrite.book.entity.BookStatus;

import java.util.List;
import java.util.UUID;

public record BookOutlineResponse(
        UUID id,
        String title,
        BookStatus status,
        Integer wordCount,
        List<OutlineSectionResponse> sections
) {
}
