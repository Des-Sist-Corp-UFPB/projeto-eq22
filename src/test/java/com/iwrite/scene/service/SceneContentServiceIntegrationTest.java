package com.iwrite.scene.service;

import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.scene.dto.SceneContentRequest;
import com.iwrite.scene.dto.ScenePlanningRequest;
import com.iwrite.scene.dto.SceneResponse;
import com.iwrite.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SceneContentServiceIntegrationTest extends PostgresIntegrationTest {

    @Test
    void patchContentRecalculatesWordCountAndPersistsContentJson() {
        StoryWorld world = createStoryWorld("content");

        SceneResponse updated = sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest("{\"type\":\"doc\",\"content\":[]}", "one two three")
        );

        assertThat(updated.contentJson()).isEqualTo("{\"type\":\"doc\",\"content\":[]}");
        assertThat(updated.contentText()).isEqualTo("one two three");
        assertThat(updated.wordCount()).isEqualTo(3);
        assertThat(sceneService.findById(world.scene().id()).wordCount()).isEqualTo(3);
    }

    @Test
    void patchContentPreservesPlanning() {
        StoryWorld world = createStoryWorld("content planning");
        SceneResponse planned = sceneService.updatePlanning(world.scene().id(), new ScenePlanningRequest(
                "Goal",
                "Conflict",
                "Outcome",
                "Notes",
                world.character().id(),
                List.of(world.character().id()),
                world.location().id(),
                List.of(world.item().id())
        ));

        SceneResponse updated = sceneService.updateContent(
                planned.id(),
                new SceneContentRequest("{\"type\":\"doc\"}", "new words")
        );

        assertThat(updated.goal()).isEqualTo("Goal");
        assertThat(updated.conflict()).isEqualTo("Conflict");
        assertThat(updated.outcome()).isEqualTo("Outcome");
        assertThat(updated.planningNotes()).isEqualTo("Notes");
        assertThat(updated.povCharacter().id()).isEqualTo(world.character().id());
        assertThat(updated.participantCharacters()).singleElement().satisfies(character ->
                assertThat(character.id()).isEqualTo(world.character().id()));
        assertThat(updated.mainLocation().id()).isEqualTo(world.location().id());
        assertThat(updated.items()).singleElement().satisfies(item ->
                assertThat(item.id()).isEqualTo(world.item().id()));
    }

    @Test
    void patchContentCountsNullAndEmptyTextAsZero() {
        StoryWorld world = createStoryWorld("empty content");

        SceneResponse nullText = sceneService.updateContent(world.scene().id(), new SceneContentRequest("{}", null));
        SceneResponse emptyText = sceneService.updateContent(world.scene().id(), new SceneContentRequest("{}", ""));

        assertThat(nullText.wordCount()).isZero();
        assertThat(emptyText.wordCount()).isZero();
    }

    @Test
    void patchContentRejectsMissingScene() {
        assertThatThrownBy(() -> sceneService.updateContent(
                UUID.randomUUID(),
                new SceneContentRequest("{}", "words")
        )).isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Scene not found");
    }
}
