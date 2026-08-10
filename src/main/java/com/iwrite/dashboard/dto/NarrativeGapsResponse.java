package com.iwrite.dashboard.dto;

public record NarrativeGapsResponse(
        int scenesWithoutPov,
        int scenesWithoutGoal,
        int scenesWithoutConflict,
        int scenesWithoutOutcome,
        int scenesWithoutMainLocation,
        int scenesWithoutParticipants
) {
}
