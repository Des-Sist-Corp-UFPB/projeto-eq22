package com.iwrite.location.dto;

import jakarta.validation.constraints.NotBlank;

public record LocationRequest(
        @NotBlank String name,
        String type,
        String description,
        String historyContext,
        String narrativeImportance,
        String notes
) {
}
