package com.iwrite.scene.dto;

import com.iwrite.location.entity.Location;

import java.util.UUID;

public record LocationSummaryResponse(
        UUID id,
        String name,
        String type
) {

    public static LocationSummaryResponse fromEntity(Location location) {
        if (location == null) {
            return null;
        }

        return new LocationSummaryResponse(
                location.getId(),
                location.getName(),
                location.getType()
        );
    }
}
