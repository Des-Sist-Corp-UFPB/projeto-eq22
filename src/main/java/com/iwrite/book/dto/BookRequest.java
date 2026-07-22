package com.iwrite.book.dto;

import com.iwrite.book.entity.BookStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.time.DayOfWeek;
import java.util.List;

public record BookRequest(
        @NotBlank String title,
        String subtitle,
        String description,
        BookStatus status,
        @Positive Integer targetWordCount,
        @Positive Integer dailyTargetWordCount,
        List<DayOfWeek> plannedWritingDays
) {

    public BookRequest(String title, String subtitle, String description, BookStatus status, Integer targetWordCount) {
        this(title, subtitle, description, status, targetWordCount, null, null);
    }

    public BookRequest(String title, String subtitle, String description, BookStatus status, Integer targetWordCount, Integer dailyTargetWordCount) {
        this(title, subtitle, description, status, targetWordCount, dailyTargetWordCount, null);
    }
}
