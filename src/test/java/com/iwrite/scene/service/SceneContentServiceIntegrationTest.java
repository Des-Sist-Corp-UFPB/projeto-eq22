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
import com.iwrite.writingprogress.ledger.entity.BookWordCountEvent;
import com.iwrite.writingprogress.ledger.entity.BookWordCountEventType;
import com.iwrite.writingprogress.ledger.repository.BookWordCountEventRepository;
import com.iwrite.writingprogress.ledger.service.WordCountRequestFingerprint;
import com.iwrite.writingprogress.repository.DailyWritingProgressRepository;
import com.iwrite.writingprogress.service.DailyWritingProgressService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_USER_ID;
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
    void sceneCreationWithInitialContentRecordsContentSaveEventAndRollup() {
        var book = createBook("ledger create nonempty");
        var section = createSection(book, "Part");
        var chapter = createChapter(section, "Chapter");

        SceneResponse scene = createScene(chapter, "Created Scene", com.iwrite.scene.entity.SceneStatus.DRAFT, 0, "one two three");

        assertThat(wordCountEventRepository.countByBookId(book.id())).isEqualTo(1);
        assertThat(wordCountEventRepository.findAll())
                .filteredOn(event -> event.getBook().getId().equals(book.id()))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getEventType()).isEqualTo(BookWordCountEventType.CONTENT_SAVE);
                    assertThat(event.getScene().getId()).isEqualTo(scene.id());
                    assertThat(event.getOriginalSceneId()).isEqualTo(scene.id());
                    assertThat(event.getProductiveWordDelta()).isEqualTo(3);
                    assertThat(event.getManuscriptWordDelta()).isEqualTo(3);
                    assertThat(event.getOperationId()).isNotNull();
                    assertThat(event.getIdempotencyKey()).isEqualTo(event.getOperationId());
                });
        assertThat(progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, book.id(), dailyWritingProgressService.today()))
                .hasValueSatisfying(progress -> {
                    assertThat(progress.getEndingManuscriptWordCount()).isEqualTo(3);
                    assertThat(progress.getProductiveWordCountChange()).isEqualTo(3);
                    assertThat(progress.getManuscriptAdjustmentWordCount()).isZero();
                });
    }

    @Test
    void sceneCreationWithZeroWordsDoesNotRecordLedgerEventOrRollup() {
        var book = createBook("ledger create empty");
        var section = createSection(book, "Part");
        var chapter = createChapter(section, "Chapter");

        createScene(chapter, "Empty Scene", com.iwrite.scene.entity.SceneStatus.DRAFT, 0, "");

        assertThat(wordCountEventRepository.countByBookId(book.id())).isZero();
        assertThat(progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, book.id(), dailyWritingProgressService.today())).isEmpty();
    }

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
        UUID operationId = UUID.randomUUID();
        long eventsBefore = wordCountEventRepository.countByBookId(world.book().id());
        var progressBefore = progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, world.book().id(), dailyWritingProgressService.today())
                .orElseThrow();

        SceneResponse updated = sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest(
                        world.scene().contentJson(),
                        world.scene().contentText(),
                        SceneVersionSource.MANUAL_SAVE,
                        world.scene().contentRevision(),
                        operationId
                )
        );

        assertThat(updated.contentRevision()).isEqualTo(world.scene().contentRevision());
        assertThat(sceneVersionRepository.countByOriginalSceneId(world.scene().id())).isZero();
        assertThat(wordCountEventRepository.countByBookId(world.book().id())).isEqualTo(eventsBefore + 1);
        assertThat(wordCountEventRepository.findByBookIdAndIdempotencyKey(world.book().id(), operationId))
                .hasValueSatisfying(event -> {
                    assertThat(event.getEventType()).isEqualTo(BookWordCountEventType.CONTENT_SAVE);
                    assertThat(event.getScene().getId()).isEqualTo(world.scene().id());
                    assertThat(event.getOriginalSceneId()).isEqualTo(world.scene().id());
                    assertThat(event.getProductiveWordDelta()).isZero();
                    assertThat(event.getManuscriptWordDelta()).isZero();
                    assertThat(event.getOperationId()).isEqualTo(operationId);
                    assertThat(event.getContentRevisionBefore()).isEqualTo(world.scene().contentRevision());
                    assertThat(event.getContentRevisionAfter()).isEqualTo(world.scene().contentRevision());
                    assertThat(event.getRequestFingerprint()).isEqualTo(WordCountRequestFingerprint.contentSave(
                            DEFAULT_USER_ID,
                            world.book().id(),
                            world.scene().id(),
                            world.scene().contentRevision(),
                            SceneVersionSource.MANUAL_SAVE,
                            world.scene().contentJson(),
                            world.scene().contentText()
                    ));
                });
        entityManager.flush();
        entityManager.clear();
        assertThat(progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, world.book().id(), dailyWritingProgressService.today()))
                .hasValueSatisfying(progress -> {
                    assertThat(progress.getEndingManuscriptWordCount()).isEqualTo(progressBefore.getEndingManuscriptWordCount());
                    assertThat(progress.getProductiveWordCountChange()).isEqualTo(progressBefore.getProductiveWordCountChange());
                    assertThat(progress.getManuscriptAdjustmentWordCount()).isEqualTo(progressBefore.getManuscriptAdjustmentWordCount());
                });
    }

    @Test
    void unchangedContentOnEmptyBookReservesOperationWithoutCreatingProgressRow() {
        var book = createBook("version unchanged empty book");
        var section = createSection(book, "Part");
        var chapter = createChapter(section, "Chapter");
        SceneResponse scene = createScene(chapter, "Empty Scene", com.iwrite.scene.entity.SceneStatus.DRAFT, 0, "");
        UUID operationId = UUID.randomUUID();

        SceneResponse updated = sceneService.updateContent(
                scene.id(),
                new SceneContentRequest(scene.contentJson(), scene.contentText(), SceneVersionSource.AUTO_SAVE, scene.contentRevision(), operationId)
        );

        assertThat(updated.contentRevision()).isEqualTo(scene.contentRevision());
        assertThat(updated.wordCount()).isZero();
        assertThat(sceneVersionRepository.countByOriginalSceneId(scene.id())).isZero();
        assertThat(wordCountEventRepository.findByBookIdAndIdempotencyKey(book.id(), operationId))
                .hasValueSatisfying(event -> {
                    assertThat(event.getEventType()).isEqualTo(BookWordCountEventType.CONTENT_SAVE);
                    assertThat(event.getProductiveWordDelta()).isZero();
                    assertThat(event.getManuscriptWordDelta()).isZero();
                    assertThat(event.getContentRevisionBefore()).isEqualTo(scene.contentRevision());
                    assertThat(event.getContentRevisionAfter()).isEqualTo(scene.contentRevision());
                    assertThat(event.getRequestFingerprint()).matches("[0-9a-f]{64}");
                });
        entityManager.flush();
        entityManager.clear();
        assertThat(progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, book.id(), dailyWritingProgressService.today()))
                .isEmpty();
    }

    @Test
    void unchangedContentRetryDoesNotDuplicateReservationOrMutateScene() {
        StoryWorld world = createStoryWorld("version unchanged retry");
        UUID operationId = UUID.randomUUID();
        SceneContentRequest request = new SceneContentRequest(
                world.scene().contentJson(),
                world.scene().contentText(),
                SceneVersionSource.AUTO_SAVE,
                world.scene().contentRevision(),
                operationId
        );
        long eventsBefore = wordCountEventRepository.countByBookId(world.book().id());

        SceneResponse first = sceneService.updateContent(world.scene().id(), request);
        SceneResponse retry = sceneService.updateContent(world.scene().id(), request);

        assertThat(retry.contentRevision()).isEqualTo(first.contentRevision());
        assertThat(retry.contentText()).isEqualTo(first.contentText());
        assertThat(sceneVersionRepository.countByOriginalSceneId(world.scene().id())).isZero();
        assertThat(wordCountEventRepository.countByBookId(world.book().id())).isEqualTo(eventsBefore + 1);
        assertThat(wordCountEventRepository.findByBookIdAndIdempotencyKey(world.book().id(), operationId)).isPresent();
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
        assertThat(progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, world.book().id(), dailyWritingProgressService.today()))
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
        long eventsBefore = wordCountEventRepository.countByBookId(world.book().id());
        var progressBefore = progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, world.book().id(), dailyWritingProgressService.today())
                .orElseThrow();

        SceneResponse updated = sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest("{}", "other words", SceneVersionSource.AUTO_SAVE, world.scene().contentRevision(), operationId)
        );

        assertThat(updated.wordCount()).isEqualTo(world.scene().wordCount());
        assertThat(updated.contentRevision()).isEqualTo(world.scene().contentRevision() + 1);
        assertThat(wordCountEventRepository.countByBookId(world.book().id())).isEqualTo(eventsBefore + 1);
        assertThat(wordCountEventRepository.findByBookIdAndIdempotencyKey(world.book().id(), operationId))
                .hasValueSatisfying(event -> {
                    assertThat(event.getProductiveWordDelta()).isZero();
                    assertThat(event.getManuscriptWordDelta()).isZero();
                    assertThat(event.getContentRevisionBefore()).isEqualTo(world.scene().contentRevision());
                    assertThat(event.getContentRevisionAfter()).isEqualTo(updated.contentRevision());
                });
        entityManager.flush();
        entityManager.clear();
        assertThat(progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, world.book().id(), dailyWritingProgressService.today()))
                .hasValueSatisfying(progress -> {
                    assertThat(progress.getEndingManuscriptWordCount()).isEqualTo(progressBefore.getEndingManuscriptWordCount());
                    assertThat(progress.getProductiveWordCountChange()).isEqualTo(progressBefore.getProductiveWordCountChange());
                    assertThat(progress.getManuscriptAdjustmentWordCount()).isEqualTo(progressBefore.getManuscriptAdjustmentWordCount());
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
        assertThat(wordCountEventRepository.countByBookId(world.book().id())).isEqualTo(2);
        entityManager.flush();
        entityManager.clear();
        assertThat(progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, world.book().id(), dailyWritingProgressService.today()))
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
    void restoreRecordsVersionRestoreAdjustmentWithoutProductiveGrowth() {
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
        UUID operationId = UUID.randomUUID();

        SceneResponse restored = sceneService.restoreVersion(
                world.scene().id(),
                versionToRestore.getId(),
                new SceneVersionRestoreRequest(updated.contentRevision(), operationId)
        );

        assertThat(restored.contentText()).isEqualTo("scene words");
        assertThat(restored.wordCount()).isEqualTo(2);
        assertThat(restored.contentRevision()).isEqualTo(updated.contentRevision() + 1);
        assertThat(wordCountEventRepository.findByBookIdAndIdempotencyKey(world.book().id(), operationId))
                .hasValueSatisfying(event -> {
                    assertThat(event.getEventType()).isEqualTo(BookWordCountEventType.VERSION_RESTORE);
                    assertThat(event.getProductiveWordDelta()).isZero();
                    assertThat(event.getManuscriptWordDelta()).isEqualTo(-1);
                    assertThat(event.getContentRevisionBefore()).isEqualTo(updated.contentRevision());
                    assertThat(event.getContentRevisionAfter()).isEqualTo(restored.contentRevision());
                });
        entityManager.flush();
        entityManager.clear();
        assertThat(progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, world.book().id(), dailyWritingProgressService.today()))
                .hasValueSatisfying(progress -> {
                    assertThat(progress.getEndWordCount()).isEqualTo(2);
                    assertThat(progress.getNetWordCountChange()).isEqualTo(3);
                    assertThat(progress.getManuscriptAdjustmentWordCount()).isEqualTo(-1);
                });
        assertThat(sceneVersionRepository.findByOriginalSceneIdOrderByCreatedAtDesc(world.scene().id()))
                .extracting(version -> version.getSource())
                .contains(SceneVersionSource.RESTORE_SAFETY, SceneVersionSource.MANUAL_SAVE);
    }

    @Test
    void identicalRestoreIsNoOp() {
        StoryWorld world = createStoryWorld("version restore noop");
        SceneResponse updated = sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest("{}", "new words here", SceneVersionSource.MANUAL_SAVE, world.scene().contentRevision())
        );
        var currentVersion = sceneVersionRepository.findByOriginalSceneIdOrderByCreatedAtDesc(world.scene().id())
                .stream()
                .filter(version -> "new words here".equals(version.getContentText()))
                .findFirst()
                .orElseThrow();
        long versionsBefore = sceneVersionRepository.countByOriginalSceneId(world.scene().id());
        long eventsBefore = wordCountEventRepository.countByBookId(world.book().id());

        SceneResponse restored = sceneService.restoreVersion(
                world.scene().id(),
                currentVersion.getId(),
                new SceneVersionRestoreRequest(updated.contentRevision(), UUID.randomUUID())
        );

        assertThat(restored.contentRevision()).isEqualTo(updated.contentRevision());
        assertThat(sceneVersionRepository.countByOriginalSceneId(world.scene().id())).isEqualTo(versionsBefore);
        assertThat(wordCountEventRepository.countByBookId(world.book().id())).isEqualTo(eventsBefore);
    }

    @Test
    void repeatedRestoreOperationIdDoesNotDuplicateLedgerOrRollup() {
        StoryWorld world = createStoryWorld("version restore retry");
        SceneResponse updated = sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest("{}", "new words here", SceneVersionSource.MANUAL_SAVE, world.scene().contentRevision())
        );
        var versionToRestore = sceneVersionRepository.findByOriginalSceneIdOrderByCreatedAtDesc(world.scene().id())
                .stream()
                .filter(version -> "scene words".equals(version.getContentText()))
                .findFirst()
                .orElseThrow();
        UUID operationId = UUID.randomUUID();
        SceneVersionRestoreRequest request = new SceneVersionRestoreRequest(updated.contentRevision(), operationId);

        SceneResponse first = sceneService.restoreVersion(world.scene().id(), versionToRestore.getId(), request);
        SceneResponse retry = sceneService.restoreVersion(world.scene().id(), versionToRestore.getId(), request);

        assertThat(retry.contentRevision()).isEqualTo(first.contentRevision());
        assertThat(wordCountEventRepository.findByBookIdAndIdempotencyKey(world.book().id(), operationId)).isPresent();
        assertThat(wordCountEventRepository.countByBookId(world.book().id())).isEqualTo(3);
        entityManager.flush();
        entityManager.clear();
        assertThat(progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, world.book().id(), dailyWritingProgressService.today()))
                .hasValueSatisfying(progress -> {
                    assertThat(progress.getEndWordCount()).isEqualTo(2);
                    assertThat(progress.getNetWordCountChange()).isEqualTo(3);
                    assertThat(progress.getManuscriptAdjustmentWordCount()).isEqualTo(-1);
                });
    }

    @Test
    void staleRestoreRevisionIsRejected() {
        StoryWorld world = createStoryWorld("version restore stale");
        SceneResponse updated = sceneService.updateContent(
                world.scene().id(),
                new SceneContentRequest("{}", "new words here", SceneVersionSource.MANUAL_SAVE, world.scene().contentRevision())
        );
        var versionToRestore = sceneVersionRepository.findByOriginalSceneIdOrderByCreatedAtDesc(world.scene().id())
                .stream()
                .filter(version -> "scene words".equals(version.getContentText()))
                .findFirst()
                .orElseThrow();

        assertThatThrownBy(() -> sceneService.restoreVersion(
                world.scene().id(),
                versionToRestore.getId(),
                new SceneVersionRestoreRequest(world.scene().contentRevision(), UUID.randomUUID())
        )).isInstanceOf(ConflictException.class);
        assertThat(sceneService.findById(world.scene().id()).contentRevision()).isEqualTo(updated.contentRevision());
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
    void sceneDeleteCreatesDeleteSafetyAndRecordsNonProductiveAdjustment() {
        StoryWorld world = createStoryWorld("version delete scene");

        sceneService.delete(world.scene().id());
        entityManager.flush();
        entityManager.clear();

        assertThat(sceneVersionRepository.findByOriginalSceneIdOrderByCreatedAtDesc(world.scene().id()))
                .singleElement()
                .satisfies(version -> {
                    assertThat(version.getScene()).isNull();
                    assertThat(version.getContentText()).isEqualTo("scene words");
                    assertThat(version.getSource()).isEqualTo(SceneVersionSource.DELETE_SAFETY);
                });
        assertThat(deleteEventsForBook(world.book().id()))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getScene()).isNull();
                    assertThat(event.getOriginalSceneId()).isEqualTo(world.scene().id());
                    assertThat(event.getProductiveWordDelta()).isZero();
                    assertThat(event.getManuscriptWordDelta()).isEqualTo(-2);
                    assertThat(event.getContentRevisionBefore()).isEqualTo(world.scene().contentRevision());
                    assertThat(event.getContentRevisionAfter()).isNull();
                });
        assertThat(progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, world.book().id(), dailyWritingProgressService.today()))
                .hasValueSatisfying(progress -> {
                    assertThat(progress.getEndWordCount()).isZero();
                    assertThat(progress.getNetWordCountChange()).isEqualTo(2);
                    assertThat(progress.getManuscriptAdjustmentWordCount()).isEqualTo(-2);
                });
    }

    @Test
    void sceneDeleteWithZeroWordsDoesNotRecordUselessLedgerEvent() {
        var book = createBook("version delete zero");
        var section = createSection(book, "Part");
        var chapter = createChapter(section, "Chapter");
        var scene = createScene(chapter, "Empty Scene", com.iwrite.scene.entity.SceneStatus.DRAFT, 0, "");

        sceneService.delete(scene.id());

        assertThat(deleteEventsForBook(book.id())).isEmpty();
    }

    @Test
    void chapterDeleteCreatesDeleteSafetyAndOneEventPerNonEmptyScene() {
        StoryWorld chapterWorld = createStoryWorld("version delete chapter");
        var secondScene = createScene(chapterWorld.chapter(), "Second chapter scene", com.iwrite.scene.entity.SceneStatus.DRAFT, 1, "second scene words");
        var emptyScene = createScene(chapterWorld.chapter(), "Empty chapter scene", com.iwrite.scene.entity.SceneStatus.DRAFT, 2, "");

        chapterService.delete(chapterWorld.chapter().id());
        entityManager.flush();
        entityManager.clear();

        assertThat(sceneVersionRepository.findByOriginalSceneIdOrderByCreatedAtDesc(chapterWorld.scene().id()))
                .singleElement()
                .satisfies(version -> assertThat(version.getSource()).isEqualTo(SceneVersionSource.DELETE_SAFETY));
        assertThat(sceneVersionRepository.findByOriginalSceneIdOrderByCreatedAtDesc(secondScene.id()))
                .singleElement()
                .satisfies(version -> assertThat(version.getSource()).isEqualTo(SceneVersionSource.DELETE_SAFETY));
        assertThat(sceneVersionRepository.findByOriginalSceneIdOrderByCreatedAtDesc(emptyScene.id()))
                .singleElement()
                .satisfies(version -> assertThat(version.getSource()).isEqualTo(SceneVersionSource.DELETE_SAFETY));

        List<BookWordCountEvent> events = deleteEventsForBook(chapterWorld.book().id());
        assertThat(events).hasSize(2);
        assertThat(events)
                .extracting(BookWordCountEvent::getOriginalSceneId)
                .containsExactlyInAnyOrder(chapterWorld.scene().id(), secondScene.id());
        assertThat(events.stream().map(BookWordCountEvent::getOperationId).collect(Collectors.toSet())).hasSize(1);
        assertThat(events.stream().map(BookWordCountEvent::getIdempotencyKey).collect(Collectors.toSet())).hasSize(2);
        assertThat(events.stream().mapToInt(BookWordCountEvent::getProductiveWordDelta).sum()).isZero();
        assertThat(events.stream().mapToInt(BookWordCountEvent::getManuscriptWordDelta).sum()).isEqualTo(-5);
        assertThat(progressRepository.findByUser_IdAndBookIdAndProgressDate(DEFAULT_USER_ID, chapterWorld.book().id(), dailyWritingProgressService.today()))
                .hasValueSatisfying(progress -> {
                    assertThat(progress.getEndWordCount()).isZero();
                    assertThat(progress.getNetWordCountChange()).isEqualTo(5);
                    assertThat(progress.getManuscriptAdjustmentWordCount()).isEqualTo(-5);
                });
    }

    @Test
    void sectionDeleteCreatesDeleteSafetyAndGroupedEventsAcrossNestedChapters() {
        StoryWorld sectionWorld = createStoryWorld("version delete section");
        var secondChapter = createChapter(sectionWorld.section(), "Second chapter");
        var secondScene = createScene(secondChapter, "Second section scene", com.iwrite.scene.entity.SceneStatus.DRAFT, 0, "nested scene words");

        sectionService.delete(sectionWorld.section().id());
        entityManager.flush();
        entityManager.clear();

        assertThat(sceneVersionRepository.findByOriginalSceneIdOrderByCreatedAtDesc(sectionWorld.scene().id()))
                .singleElement()
                .satisfies(version -> assertThat(version.getSource()).isEqualTo(SceneVersionSource.DELETE_SAFETY));
        assertThat(sceneVersionRepository.findByOriginalSceneIdOrderByCreatedAtDesc(secondScene.id()))
                .singleElement()
                .satisfies(version -> assertThat(version.getSource()).isEqualTo(SceneVersionSource.DELETE_SAFETY));

        List<BookWordCountEvent> events = deleteEventsForBook(sectionWorld.book().id());
        assertThat(events).hasSize(2);
        assertThat(events)
                .extracting(BookWordCountEvent::getOriginalSceneId)
                .containsExactlyInAnyOrder(sectionWorld.scene().id(), secondScene.id());
        assertThat(events.stream().map(BookWordCountEvent::getOperationId).collect(Collectors.toSet())).hasSize(1);
        assertThat(events.stream().map(BookWordCountEvent::getIdempotencyKey).collect(Collectors.toSet())).hasSize(2);
        assertThat(events.stream().mapToInt(BookWordCountEvent::getProductiveWordDelta).sum()).isZero();
        assertThat(events.stream().mapToInt(BookWordCountEvent::getManuscriptWordDelta).sum()).isEqualTo(-5);
    }

    @Test
    void bookDeleteCascadesSceneVersionsAndLedgerRows() {
        StoryWorld world = createStoryWorld("version delete book");
        sceneService.delete(world.scene().id());

        entityManager.flush();
        entityManager.clear();
        assertThat(sceneVersionRepository.countByOriginalSceneId(world.scene().id())).isEqualTo(1);
        assertThat(deleteEventsForBook(world.book().id())).hasSize(1);

        bookService.delete(world.book().id());
        entityManager.flush();
        entityManager.clear();

        assertThat(sceneVersionRepository.countByOriginalSceneId(world.scene().id())).isZero();
        assertThat(deleteEventsForBook(world.book().id())).isEmpty();
    }

    @Test
    void patchContentRejectsMissingScene() {
        assertThatThrownBy(() -> sceneService.updateContent(
                UUID.randomUUID(),
                new SceneContentRequest("{}", "words")
        )).isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Scene not found");
    }

    private List<BookWordCountEvent> deleteEventsForBook(UUID bookId) {
        return wordCountEventRepository.findAll()
                .stream()
                .filter(event -> event.getBook().getId().equals(bookId))
                .filter(event -> event.getEventType() == BookWordCountEventType.SCENE_DELETE)
                .toList();
    }
}
