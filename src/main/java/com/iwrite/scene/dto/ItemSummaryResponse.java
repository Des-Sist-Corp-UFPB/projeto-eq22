package com.iwrite.scene.dto;

import com.iwrite.item.entity.Item;

import java.util.UUID;

public record ItemSummaryResponse(
        UUID id,
        String name,
        String type
) {

    public static ItemSummaryResponse fromEntity(Item item) {
        if (item == null) {
            return null;
        }

        return new ItemSummaryResponse(
                item.getId(),
                item.getName(),
                item.getType()
        );
    }
}
