package com.iwrite.book.dto;

import com.iwrite.book.entity.Book;
import com.iwrite.book.entity.BookStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record BookResponse(
        UUID id,
        String title,
        String subtitle,
        String description,
        BookStatus status,
        Integer targetWordCount,
        Integer dailyTargetWordCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static BookResponse fromEntity(Book book) {
        return new BookResponse(
                book.getId(),
                book.getTitle(),
                book.getSubtitle(),
                book.getDescription(),
                book.getStatus(),
                book.getTargetWordCount(),
                book.getDailyTargetWordCount(),
                book.getCreatedAt(),
                book.getUpdatedAt()
        );
    }
}
