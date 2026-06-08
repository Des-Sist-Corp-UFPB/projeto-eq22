package com.iwrite.scene.service;

import com.iwrite.common.exception.ConflictException;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.scene.dto.SceneContentRequest;
import com.iwrite.scene.dto.ScenePlanningRequest;
import com.iwrite.scene.dto.SceneResponse;
import com.iwrite.sceneversion.dto.SceneVersionRestoreRequest;
import com.iwrite.sceneversion.entity.SceneVersionSource;
import com.iwrite.sceneversion.repository.SceneVersionRepository;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.writingprogress.repository.DailyWritingProgressRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SceneContentServiceIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private SceneVersionRepository sceneVersionRepository;

    @Autowired
    private DailyWritingProgressRepository progressRepository;

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
        SceneResponse emptyText = sceneService.updateContent(world.scene().id(), new SceneContentRequest("{}", "", null, nullText.contentRevision()));

        assertThat(nullText.wordCount()).isZero();
        assertThat(emptyText.wordCount()).isZero();
    }

    @Test
    void changedContentCreatesSnapshotOfOutgoingPersistedContentBeforeOverwrite() {
        StoryWorld world = createStoryWorld("version outgoing");

        SceneResponse updated = sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest("{\"type\":\"doc\"}", "new words", SceneVersionSource.MANUAL_SAVE, world.scene().contentRevision())
        );

        assertThat(updated.contentRevision()).isEqualTo(world.scene().contentRevision() + 1);
        assertThat(sceneVersionRepository.findByOriginalSceneIdOrderByCreatedAtDesc(world.scene().id()))
                .singleElement()
                .satisfies(version -> {
                    assertThat(version.getScene().getId()).isEqualTo(world.scene().id());
                    assertThat(version.getContentText()).isEqualTo("scene words");
                    assertThat(version.getSource()).isEqualTo(SceneVersionSource.MANUAL_SAVE);
                });
    }

    @Test
    void unchangedContentDoesNotCreateSnapshotOrIncrementRevision() {
        StoryWorld world = createStoryWorld("version unchanged");

        SceneResponse updated = sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest(world.scene().contentJson(), world.scene().contentText(), SceneVersionSource.MANUAL_SAVE, world.scene().contentRevision())
        );

        assertThat(updated.contentRevision()).isEqualTo(world.scene().contentRevision());
        assertThat(sceneVersionRepository.countByOriginalSceneId(world.scene().id())).isZero();
    }

    @Test
    void staleContentRevisionIsRejected() {
        StoryWorld world = createStoryWorld("version stale");
        SceneResponse updated = sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest("{}", "first", SceneVersionSource.MANUAL_SAVE, world.scene().contentRevision())
        );

        assertThatThrownBy(() -> sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest("{}", "second", SceneVersionSource.MANUAL_SAVE, world.scene().contentRevision())
        )).isInstanceOf(ConflictException.class);
        assertThat(sceneService.findById(world.scene().id()).contentRevision()).isEqualTo(updated.contentRevision());
    }

    @Test
    void restoreCreatesSafetySnapshotAndDoesNotRecordWritingProgress() {
        StoryWorld world = createStoryWorld("version restore");
        SceneResponse updated = sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest("{}", "new words here", SceneVersionSource.MANUAL_SAVE, world.scene().contentRevision())
        );
        var versionToRestore = sceneVersionRepository.findByOriginalSceneIdOrderByCreatedAtDesc(world.scene().id())
                .getFirst();
        long progressRowsBefore = progressRepository.count();

        SceneResponse restored = sceneService.restoreVersion(
                world.scene().id(),
                versionToRestore.getId(),
                new SceneVersionRestoreRequest(updated.contentRevision())
        );

        assertThat(restored.contentText()).isEqualTo("scene words");
        assertThat(restored.wordCount()).isEqualTo(2);
        assertThat(restored.contentRevision()).isEqualTo(updated.contentRevision() + 1);
        assertThat(progressRepository.count()).isEqualTo(progressRowsBefore);
        assertThat(sceneVersionRepository.findByOriginalSceneIdOrderByCreatedAtDesc(world.scene().id()))
                .extracting(version -> version.getSource())
                .contains(SceneVersionSource.RESTORE_SAFETY, SceneVersionSource.MANUAL_SAVE);
    }

    @Test
    void sceneDeleteCreatesDeleteSafetySnapshotAndRetainsOrphanVersion() {
        StoryWorld world = createStoryWorld("version delete scene");

        sceneService.delete(world.scene().id());

        assertThat(sceneVersionRepository.findByOriginalSceneIdOrderByCreatedAtDesc(world.scene().id()))
                .singleElement()
                .satisfies(version -> {
                    assertThat(version.getScene()).isNull();
                    assertThat(version.getContentText()).isEqualTo("scene words");
                    assertThat(version.getSource()).isEqualTo(SceneVersionSource.DELETE_SAFETY);
                });
    }

    @Test
    void chapterAndSectionDeleteCreateDeleteSafetySnapshotsForChildScenes() {
        StoryWorld chapterWorld = createStoryWorld("version delete chapter");
        StoryWorld sectionWorld = createStoryWorld("version delete section");

        chapterService.delete(chapterWorld.chapter().id());
        sectionService.delete(sectionWorld.section().id());

        assertThat(sceneVersionRepository.findByOriginalSceneIdOrderByCreatedAtDesc(chapterWorld.scene().id()))
                .singleElement()
                .satisfies(version -> assertThat(version.getSource()).isEqualTo(SceneVersionSource.DELETE_SAFETY));
        assertThat(sceneVersionRepository.findByOriginalSceneIdOrderByCreatedAtDesc(sectionWorld.scene().id()))
                .singleElement()
                .satisfies(version -> assertThat(version.getSource()).isEqualTo(SceneVersionSource.DELETE_SAFETY));
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
