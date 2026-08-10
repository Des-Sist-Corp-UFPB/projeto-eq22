package com.iwrite.location.dto;

public record LocationUpdateRequest(
        String name,
        String type,
        String description,
        String historyContext,
        String narrativeImportance,
        String notes
) {
}
