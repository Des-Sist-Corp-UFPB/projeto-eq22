package com.iwrite.scene.service;

import com.iwrite.common.exception.BadRequestException;
import com.iwrite.scene.dto.ScenePlanningRequest;
import com.iwrite.scene.dto.SceneResponse;
import com.iwrite.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenePlanningServiceIntegrationTest extends PostgresIntegrationTest {

    @Test
    void savesPlanningFieldsAndLinks() {
        StoryWorld world = createStoryWorld("planning save");

        SceneResponse planned = sceneService.updatePlanning(world.scene().id(), new ScenePlanningRequest(
                "Find the map",
                "The map is false",
                "They choose a risky path",
                "Mirror the midpoint decision",
                world.character().id(),
                List.of(world.character().id()),
                world.location().id(),
                List.of(world.item().id())
        ));

        assertThat(planned.goal()).isEqualTo("Find the map");
        assertThat(planned.conflict()).isEqualTo("The map is false");
        assertThat(planned.outcome()).isEqualTo("They choose a risky path");
        assertThat(planned.planningNotes()).isEqualTo("Mirror the midpoint decision");
        assertThat(planned.povCharacter().id()).isEqualTo(world.character().id());
        assertThat(planned.mainLocation().id()).isEqualTo(world.location().id());
        assertThat(planned.participantCharacters()).singleElement().satisfies(character ->
                assertThat(character.id()).isEqualTo(world.character().id()));
        assertThat(planned.items()).singleElement().satisfies(item ->
                assertThat(item.id()).isEqualTo(world.item().id()));
    }

    @Test
    void clearsNullablePlanningLinksAndCollections() {
        StoryWorld world = createStoryWorld("planning clear");
        sceneService.updatePlanning(world.scene().id(), new ScenePlanningRequest(
                "Goal",
                "Conflict",
                "Outcome",
                "Notes",
                world.character().id(),
                List.of(world.character().id()),
                world.location().id(),
                List.of(world.item().id())
        ));

        SceneResponse cleared = sceneService.updatePlanning(world.scene().id(), new ScenePlanningRequest(
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                List.of()
        ));

        assertThat(cleared.goal()).isNull();
        assertThat(cleared.conflict()).isNull();
        assertThat(cleared.outcome()).isNull();
        assertThat(cleared.planningNotes()).isNull();
        assertThat(cleared.povCharacter()).isNull();
        assertThat(cleared.mainLocation()).isNull();
        assertThat(cleared.participantCharacters()).isEmpty();
        assertThat(cleared.items()).isEmpty();
    }

    @Test
    void rejectsNullIdsInsidePlanningCollections() {
        StoryWorld world = createStoryWorld("planning null ids");

        assertThatThrownBy(() -> sceneService.updatePlanning(world.scene().id(), new ScenePlanningRequest(
                "Goal",
                "Conflict",
                "Outcome",
                "Notes",
                world.character().id(),
                java.util.Arrays.asList(world.character().id(), null),
                world.location().id(),
                List.of(world.item().id())
        ))).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("participantCharacterIds must not contain null IDs");

        assertThatThrownBy(() -> sceneService.updatePlanning(world.scene().id(), new ScenePlanningRequest(
                "Goal",
                "Conflict",
                "Outcome",
                "Notes",
                world.character().id(),
                List.of(world.character().id()),
                world.location().id(),
                java.util.Arrays.asList(world.item().id(), null)
        ))).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("itemIds must not contain null IDs");
    }
}
