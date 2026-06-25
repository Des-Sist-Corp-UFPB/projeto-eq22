package com.iwrite.book.dto;

import com.iwrite.book.entity.Book;
import com.iwrite.book.entity.BookAccessLevel;
import com.iwrite.book.entity.BookStatus;

import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record BookResponse(
        UUID id,
        String title,
        String subtitle,
        String description,
        BookStatus status,
        Integer targetWordCount,
        Integer dailyTargetWordCount,
        List<DayOfWeek> plannedWritingDays,
        BookAccessLevel accessLevel,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static BookResponse fromEntity(Book book, List<DayOfWeek> plannedWritingDays, BookAccessLevel accessLevel) {
        return new BookResponse(
                book.getId(),
                book.getTitle(),
                book.getSubtitle(),
                book.getDescription(),
                book.getStatus(),
                book.getTargetWordCount(),
                book.getDailyTargetWordCount(),
                plannedWritingDays,
                accessLevel,
                book.getCreatedAt(),
                book.getUpdatedAt()
        );
    }
}
