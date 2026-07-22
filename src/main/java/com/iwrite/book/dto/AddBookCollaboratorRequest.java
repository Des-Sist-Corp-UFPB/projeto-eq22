package com.iwrite.book.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AddBookCollaboratorRequest(@NotNull UUID userId) {
}
