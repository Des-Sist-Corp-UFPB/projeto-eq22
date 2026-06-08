package com.iwrite.scene.service;

import com.iwrite.common.exception.ConflictException;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.scene.dto.SceneContentRequest;
import com.iwrite.scene.dto.ScenePlanningRequest;
import com.iwrite.scene.dto.SceneResponse;
import com.iwrite.scene.dto.SceneUpdateRequest;
import com.iwrite.sceneversion.dto.SceneVersionRestoreRequest;
import com.iwrite.sceneversion.entity.SceneVersionSource;
import com.iwrite.sceneversion.repository.SceneVersionRepository;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.writingprogress.ledger.entity.BookWordCountEventType;
import com.iwrite.writingprogress.ledger.repository.BookWordCountEventRepository;
import com.iwrite.writingprogress.repository.DailyWritingProgressRepository;
import com.iwrite.writingprogress.service.DailyWritingProgressService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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

    @Autowired
    private DailyWritingProgressService dailyWritingProgressService;

    @Autowired
    private BookWordCountEventRepository wordCountEventRepository;

    @PersistenceContext
    private EntityManager entityManager;

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
                .anySatisfy(version -> {
                    assertThat(version.getScene().getId()).isEqualTo(world.scene().id());
                    assertThat(version.getContentText()).isEqualTo("scene words");
                    assertThat(version.getSource()).isEqualTo(SceneVersionSource.MANUAL_SAVE);
                });
    }

    @Test
    void unchangedContentDoesNotCreateSnapshotOrIncrementRevision() {
        StoryWorld world = createStoryWorld("version unchanged");
        long eventsBefore = wordCountEventRepository.countByBookId(world.book().id());

        SceneResponse updated = sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest(world.scene().contentJson(), world.scene().contentText(), SceneVersionSource.MANUAL_SAVE, world.scene().contentRevision())
        );

        assertThat(updated.contentRevision()).isEqualTo(world.scene().contentRevision());
        assertThat(sceneVersionRepository.countByOriginalSceneId(world.scene().id())).isZero();
        assertThat(wordCountEventRepository.countByBookId(world.book().id())).isEqualTo(eventsBefore);
    }

    @Test
    void emptyOutgoingContentDoesNotCreateUselessSnapshot() {
        StoryWorld world = createStoryWorld("version empty outgoing");
        SceneResponse emptied = sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest(null, null, SceneVersionSource.MANUAL_SAVE, world.scene().contentRevision())
        );

        SceneResponse updated = sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest("{\"type\":\"doc\"}", "new words", SceneVersionSource.MANUAL_SAVE, emptied.contentRevision())
        );

        assertThat(updated.contentRevision()).isEqualTo(emptied.contentRevision() + 1);
        assertThat(sceneVersionRepository.findByOriginalSceneIdOrderByCreatedAtDesc(world.scene().id()))
                .hasSize(2)
                .anySatisfy(version -> assertThat(version.getContentText()).isEqualTo("scene words"))
                .anySatisfy(version -> assertThat(version.getContentText()).isEqualTo("new words"));
    }

    @Test
    void autosaveThrottlesCheckpointsButManualSaveBypassesThrottle() {
        StoryWorld world = createStoryWorld("version autosave throttle");
        SceneResponse autosaved = sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest("{\"type\":\"doc\"}", "autosaved words", SceneVersionSource.AUTO_SAVE, world.scene().contentRevision())
        );

        SceneResponse throttledAutosave = sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest("{\"type\":\"doc\"}", "autosaved words again", SceneVersionSource.AUTO_SAVE, autosaved.contentRevision())
        );

        assertThat(throttledAutosave.contentRevision()).isEqualTo(autosaved.contentRevision() + 1);
        assertThat(sceneVersionRepository.countByOriginalSceneId(world.scene().id())).isEqualTo(1);
    }

    @Test
    void changedAutosaveRecordsContentSaveEventAndRollupUpdate() {
        StoryWorld world = createStoryWorld("ledger autosave");
        UUID operationId = UUID.randomUUID();

        SceneResponse updated = sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest("{}", "one two three", SceneVersionSource.AUTO_SAVE, world.scene().contentRevision(), operationId)
        );

        assertThat(updated.wordCount()).isEqualTo(3);
        assertThat(wordCountEventRepository.findByBookIdAndIdempotencyKey(world.book().id(), operationId))
                .hasValueSatisfying(event -> {
                    assertThat(event.getEventType()).isEqualTo(BookWordCountEventType.CONTENT_SAVE);
                    assertThat(event.getScene().getId()).isEqualTo(world.scene().id());
                    assertThat(event.getProductiveWordDelta()).isEqualTo(1);
                    assertThat(event.getManuscriptWordDelta()).isEqualTo(1);
                    assertThat(event.getOperationId()).isEqualTo(operationId);
                    assertThat(event.getContentRevisionBefore()).isEqualTo(world.scene().contentRevision());
                    assertThat(event.getContentRevisionAfter()).isEqualTo(updated.contentRevision());
                });
        entityManager.flush();
        entityManager.clear();
        assertThat(progressRepository.findByBookIdAndProgressDate(world.book().id(), dailyWritingProgressService.today()))
                .hasValueSatisfying(progress -> {
                    assertThat(progress.getEndWordCount()).isEqualTo(3);
                    assertThat(progress.getNetWordCountChange()).isEqualTo(3);
                    assertThat(progress.getManuscriptAdjustmentWordCount()).isZero();
                });
    }

    @Test
    void sameWordCountTextRewriteRecordsZeroDeltaEventAndIncrementsRevision() {
        StoryWorld world = createStoryWorld("ledger zero delta");
        UUID operationId = UUID.randomUUID();

        SceneResponse updated = sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest("{}", "other words", SceneVersionSource.AUTO_SAVE, world.scene().contentRevision(), operationId)
        );

        assertThat(updated.wordCount()).isEqualTo(world.scene().wordCount());
        assertThat(updated.contentRevision()).isEqualTo(world.scene().contentRevision() + 1);
        assertThat(wordCountEventRepository.findByBookIdAndIdempotencyKey(world.book().id(), operationId))
                .hasValueSatisfying(event -> {
                    assertThat(event.getProductiveWordDelta()).isZero();
                    assertThat(event.getManuscriptWordDelta()).isZero();
                    assertThat(event.getContentRevisionBefore()).isEqualTo(world.scene().contentRevision());
                    assertThat(event.getContentRevisionAfter()).isEqualTo(updated.contentRevision());
                });
    }

    @Test
    void manualSaveContentRemainsRecoverableWhenFollowedByThrottledAutosaveOverwrite() {
        StoryWorld world = createStoryWorld("version manual confirmed");
        SceneResponse autosaved = sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest("{\"type\":\"doc\"}", "autosaved words", SceneVersionSource.AUTO_SAVE, world.scene().contentRevision())
        );
        SceneResponse manualSaved = sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest("{\"type\":\"doc\"}", "confirmed manual words", SceneVersionSource.MANUAL_SAVE, autosaved.contentRevision())
        );

        sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest("{\"type\":\"doc\"}", "quick autosave overwrite", SceneVersionSource.AUTO_SAVE, manualSaved.contentRevision())
        );

        assertThat(sceneVersionRepository.findByOriginalSceneIdOrderByCreatedAtDesc(world.scene().id()))
                .anySatisfy(version -> {
                    assertThat(version.getSource()).isEqualTo(SceneVersionSource.MANUAL_SAVE);
                    assertThat(version.getContentText()).isEqualTo("confirmed manual words");
                });
    }

    @Test
    void repeatedOperationIdDoesNotDuplicateLedgerOrRollup() {
        StoryWorld world = createStoryWorld("ledger retry");
        UUID operationId = UUID.randomUUID();
        SceneContentRequest request = new SceneContentRequest(
                "{}",
                "one two three",
                SceneVersionSource.AUTO_SAVE,
                world.scene().contentRevision(),
                operationId
        );

        SceneResponse first = sceneService.updateContent(world.scene().id(), request);
        SceneResponse retry = sceneService.updateContent(world.scene().id(), request);

        assertThat(retry.contentRevision()).isEqualTo(first.contentRevision());
        assertThat(wordCountEventRepository.countByBookId(world.book().id())).isEqualTo(1);
        entityManager.flush();
        entityManager.clear();
        assertThat(progressRepository.findByBookIdAndProgressDate(world.book().id(), dailyWritingProgressService.today()))
                .hasValueSatisfying(progress -> {
                    assertThat(progress.getEndWordCount()).isEqualTo(3);
                    assertThat(progress.getNetWordCountChange()).isEqualTo(3);
                });
    }

    @Test
    void metadataOnlyUpdateDoesNotChangeContentRevision() {
        StoryWorld world = createStoryWorld("version metadata");

        SceneResponse updated = sceneService.update(
                world.scene().id(),
                new SceneUpdateRequest("Renamed scene", "New summary", null, null)
        );

        assertThat(updated.contentRevision()).isEqualTo(world.scene().contentRevision());
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
                .stream()
                .filter(version -> "scene words".equals(version.getContentText()))
                .findFirst()
                .orElseThrow();
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
    void restoreRejectsCrossSceneAndOrphanVersions() {
        StoryWorld sourceWorld = createStoryWorld("version restore source");
        StoryWorld targetWorld = createStoryWorld("version restore target");
        SceneResponse updated = sceneService.updateContent(
                sourceWorld.scene().id(),
                new SceneContentRequest("{}", "new source words", SceneVersionSource.MANUAL_SAVE, sourceWorld.scene().contentRevision())
        );
        var sourceVersion = sceneVersionRepository.findByOriginalSceneIdOrderByCreatedAtDesc(sourceWorld.scene().id())
                .getFirst();
        UUID sourceVersionId = sourceVersion.getId();

        assertThatThrownBy(() -> sceneService.restoreVersion(
                targetWorld.scene().id(),
                sourceVersionId,
                new SceneVersionRestoreRequest(targetWorld.scene().contentRevision())
        )).isInstanceOf(ResourceNotFoundException.class);

        entityManager.flush();
        entityManager.clear();

        sceneService.delete(sourceWorld.scene().id());
        entityManager.flush();
        entityManager.clear();

        assertThatThrownBy(() -> sceneService.restoreVersion(
                sourceWorld.scene().id(),
                sourceVersionId,
                new SceneVersionRestoreRequest(updated.contentRevision())
        )).isInstanceOf(ResourceNotFoundException.class);
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
    void chapterDeleteCreatesDeleteSafetySnapshotForChildScene() {
        StoryWorld chapterWorld = createStoryWorld("version delete chapter");

        chapterService.delete(chapterWorld.chapter().id());

        assertThat(sceneVersionRepository.findByOriginalSceneIdOrderByCreatedAtDesc(chapterWorld.scene().id()))
                .singleElement()
                .satisfies(version -> assertThat(version.getSource()).isEqualTo(SceneVersionSource.DELETE_SAFETY));
    }

    @Test
    void sectionDeleteCreatesDeleteSafetySnapshotForChildScene() {
        StoryWorld sectionWorld = createStoryWorld("version delete section");

        sectionService.delete(sectionWorld.section().id());

        assertThat(sceneVersionRepository.findByOriginalSceneIdOrderByCreatedAtDesc(sectionWorld.scene().id()))
                .singleElement()
                .satisfies(version -> assertThat(version.getSource()).isEqualTo(SceneVersionSource.DELETE_SAFETY));
    }

    @Test
    void bookDeleteCascadesSceneVersions() {
        StoryWorld world = createStoryWorld("version delete book");
        sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest("{}", "new words", SceneVersionSource.MANUAL_SAVE, world.scene().contentRevision())
        );

        entityManager.flush();
        entityManager.clear();

        bookService.delete(world.book().id());
        entityManager.flush();
        entityManager.clear();

        assertThat(sceneVersionRepository.countByOriginalSceneId(world.scene().id())).isZero();
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
