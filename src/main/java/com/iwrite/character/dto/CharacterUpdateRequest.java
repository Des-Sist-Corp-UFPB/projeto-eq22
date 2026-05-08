package com.iwrite.character.dto;

import jakarta.validation.constraints.PositiveOrZero;

public record CharacterUpdateRequest(
        String name,
        String nickname,
        @PositiveOrZero Integer age,
        String sex,
        String narrativeFunction,
        String goal,
        String conflict,
        String arc,
        String physicalDescription,
        String personality,
        String biography,
        String notes
) {
}
