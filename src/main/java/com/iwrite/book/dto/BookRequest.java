package com.iwrite.book.dto;

import com.iwrite.book.entity.BookStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record BookRequest(
        @NotBlank String title,
        String subtitle,
        String description,
        BookStatus status,
        @Positive Integer targetWordCount,
        @Positive Integer dailyTargetWordCount
) {

    public BookRequest(String title, String subtitle, String description, BookStatus status, Integer targetWordCount) {
        this(title, subtitle, description, status, targetWordCount, null);
    }
}
