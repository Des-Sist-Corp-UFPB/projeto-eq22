package com.iwrite.item.service;

import com.iwrite.common.exception.BadRequestException;
import com.iwrite.item.dto.ItemRequest;
import com.iwrite.item.dto.ItemResponse;
import com.iwrite.item.dto.ItemUpdateRequest;
import com.iwrite.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ItemOwnerServiceIntegrationTest extends PostgresIntegrationTest {

    @Test
    void createsItemWithoutOwner() {
        StoryWorld world = createStoryWorld("owner none");

        ItemResponse item = itemService.create(world.book().id(), new ItemRequest(
                "Unclaimed key",
                "Key",
                null,
                null,
                null,
                null,
                null
        ));

        assertThat(item.currentOwnerCharacterId()).isNull();
        assertThat(item.currentOwnerCharacter()).isNull();
    }

    @Test
    void createsItemWithOwnerAndReturnsFriendlyOwnerSummary() {
        StoryWorld world = createStoryWorld("owner summary");

        ItemResponse item = itemService.create(world.book().id(), new ItemRequest(
                "Owned key",
                "Key",
                null,
                null,
                world.character().id(),
                null,
                null
        ));
        ItemResponse detail = itemService.findById(item.id());

        assertThat(detail.currentOwnerCharacterId()).isEqualTo(world.character().id());
        assertThat(detail.currentOwnerCharacter()).isNotNull();
        assertThat(detail.currentOwnerCharacter().id()).isEqualTo(world.character().id());
        assertThat(detail.currentOwnerCharacter().name()).isEqualTo(world.character().name());
    }

    @Test
    void rejectsOwnerFromAnotherBookOnCreate() {
        StoryWorld world = createStoryWorld("owner primary");
        StoryWorld other = createStoryWorld("owner other");

        assertThatThrownBy(() -> itemService.create(world.book().id(), new ItemRequest(
                "Foreign owned key",
                "Key",
                null,
                null,
                other.character().id(),
                null,
                null
        ))).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("currentOwnerCharacterId must belong to the same book");
    }

    @Test
    void updateCanPreserveAndClearOwnerWithoutBreakingResponse() {
        StoryWorld world = createStoryWorld("owner update");

        ItemUpdateRequest omittedOwner = new ItemUpdateRequest();
        omittedOwner.setType("Relic");
        ItemResponse preserved = itemService.update(world.item().id(), omittedOwner);

        assertThat(preserved.currentOwnerCharacterId()).isEqualTo(world.character().id());
        assertThat(preserved.currentOwnerCharacter().name()).isEqualTo(world.character().name());

        ItemUpdateRequest clearOwner = new ItemUpdateRequest();
        clearOwner.setCurrentOwnerCharacterId(null);
        ItemResponse cleared = itemService.update(world.item().id(), clearOwner);

        assertThat(cleared.currentOwnerCharacterId()).isNull();
        assertThat(cleared.currentOwnerCharacter()).isNull();
        assertThat(itemService.findById(world.item().id()).currentOwnerCharacter()).isNull();
    }
}
