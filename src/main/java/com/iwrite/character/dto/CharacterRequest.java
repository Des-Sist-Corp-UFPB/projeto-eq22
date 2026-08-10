package com.iwrite.character.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

public record CharacterRequest(
        @NotBlank String name,
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
