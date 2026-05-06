package com.iwrite.book.dto;

import com.iwrite.book.entity.BookStatus;
import jakarta.validation.constraints.NotBlank;

public record BookRequest(
        @NotBlank String title,
        String subtitle,
        String description,
        BookStatus status
) {
}
