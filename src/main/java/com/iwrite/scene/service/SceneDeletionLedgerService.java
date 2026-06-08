package com.iwrite.scene.service;

import com.iwrite.scene.entity.Scene;
import com.iwrite.scene.repository.SceneRepository;
import com.iwrite.sceneversion.service.SceneVersionService;
import com.iwrite.writingprogress.ledger.entity.BookWordCountEventType;
import com.iwrite.writingprogress.ledger.service.WordCountEventCommand;
import com.iwrite.writingprogress.ledger.service.WordCountEventService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class SceneDeletionLedgerService {

    private final SceneVersionService sceneVersionService;
    private final WordCountEventService wordCountEventService;
    private final SceneRepository sceneRepository;

    public SceneDeletionLedgerService(
            SceneVersionService sceneVersionService,
            WordCountEventService wordCountEventService,
            SceneRepository sceneRepository
    ) {
        this.sceneVersionService = sceneVersionService;
        this.wordCountEventService = wordCountEventService;
        this.sceneRepository = sceneRepository;
    }

    public void prepareSceneDelete(Scene scene) {
        prepareSceneDeletes(List.of(scene), UUID.randomUUID());
    }

    public void prepareSceneDeletes(List<Scene> scenes, UUID operationId) {
        if (scenes.isEmpty()) {
            return;
        }

        List<DeletedSceneFacts> deletedScenes = scenes.stream()
                .map(DeletedSceneFacts::fromScene)
                .toList();
        UUID bookId = deletedScenes.getFirst().bookId();
        int totalBefore = Math.toIntExact(sceneRepository.sumWordCountByBookId(bookId));

        sceneVersionService.checkpointBeforeDelete(scenes);

        int removedWordCount = 0;
        for (DeletedSceneFacts deletedScene : deletedScenes) {
            int sceneWordCount = deletedScene.wordCount();
            if (sceneWordCount == 0) {
                continue;
            }

            removedWordCount += sceneWordCount;
            wordCountEventService.record(new WordCountEventCommand(
                    bookId,
                    deletedScene.sceneId(),
                    deletedScene.sceneId(),
                    deletedScene.sceneTitle(),
                    BookWordCountEventType.SCENE_DELETE,
                    0,
                    -sceneWordCount,
                    operationId,
                    UUID.randomUUID(),
                    deletedScene.contentRevision(),
                    null,
                    totalBefore - removedWordCount
            ));
        }
    }

    private record DeletedSceneFacts(
            UUID bookId,
            UUID sceneId,
            String sceneTitle,
            int wordCount,
            Long contentRevision
    ) {

        static DeletedSceneFacts fromScene(Scene scene) {
            return new DeletedSceneFacts(
                    scene.getBook().getId(),
                    scene.getId(),
                    scene.getTitle(),
                    scene.getWordCount() == null ? 0 : scene.getWordCount(),
                    scene.getContentRevision()
            );
        }
    }
}
