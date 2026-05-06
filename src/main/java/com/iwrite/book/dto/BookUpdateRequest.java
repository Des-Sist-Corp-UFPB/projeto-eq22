package com.iwrite.book.dto;

import com.iwrite.book.entity.BookStatus;

public record BookUpdateRequest(
        String title,
        String subtitle,
        String description,
        BookStatus status
) {
}
