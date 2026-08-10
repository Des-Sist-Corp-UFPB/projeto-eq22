package com.iwrite.common.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record ReorderRequest(
        @NotEmpty
        List<@NotNull UUID> orderedIds
) {
}
