package com.iwrite.scene.service;

import com.iwrite.book.entity.Book;
import com.iwrite.book.service.BookService;
import com.iwrite.chapter.entity.Chapter;
import com.iwrite.chapter.service.ChapterService;
import com.iwrite.character.entity.Character;
import com.iwrite.character.service.CharacterService;
import com.iwrite.common.dto.ReorderRequest;
import com.iwrite.common.exception.BadRequestException;
import com.iwrite.common.exception.ConflictException;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.common.validation.RequestValidation;
import com.iwrite.common.wordcount.WordCountService;
import com.iwrite.item.entity.Item;
import com.iwrite.item.service.ItemService;
import com.iwrite.location.entity.Location;
import com.iwrite.location.service.LocationService;
import com.iwrite.scene.dto.SceneContentRequest;
import com.iwrite.scene.dto.ScenePlanningRequest;
import com.iwrite.scene.dto.SceneRequest;
import com.iwrite.scene.dto.SceneResponse;
import com.iwrite.scene.dto.SceneUpdateRequest;
import com.iwrite.scene.entity.Scene;
import com.iwrite.scene.entity.SceneStatus;
import com.iwrite.scene.repository.SceneRepository;
import com.iwrite.sceneversion.dto.SceneVersionRestoreRequest;
import com.iwrite.sceneversion.entity.SceneVersion;
import com.iwrite.sceneversion.entity.SceneVersionSource;
import com.iwrite.sceneversion.service.SceneVersionService;
import com.iwrite.user.context.CurrentUserProvider;
import com.iwrite.writingprogress.ledger.entity.BookWordCountEvent;
import com.iwrite.writingprogress.ledger.entity.BookWordCountEventType;
import com.iwrite.writingprogress.ledger.repository.BookWordCountEventRepository;
import com.iwrite.writingprogress.ledger.service.WordCountEventCommand;
import com.iwrite.writingprogress.ledger.service.WordCountEventConflictException;
import com.iwrite.writingprogress.ledger.service.WordCountEventRecordResult;
import com.iwrite.writingprogress.ledger.service.WordCountEventService;
import com.iwrite.writingprogress.ledger.service.WordCountRequestFingerprint;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SceneService {

    private final SceneRepository sceneRepository;
    private final ChapterService chapterService;
    private final WordCountService wordCountService;
    private final CharacterService characterService;
    private final LocationService locationService;
    private final ItemService itemService;
    private final ScenePlanningCompletenessService planningCompletenessService;
    private final SceneVersionService sceneVersionService;
    private final SceneDeletionLedgerService sceneDeletionLedgerService;
    private final WordCountEventService wordCountEventService;
    private final BookWordCountEventRepository wordCountEventRepository;
    private final BookService bookService;
    private final CurrentUserProvider currentUserProvider;

    public SceneService(
            SceneRepository sceneRepository,
            ChapterService chapterService,
            WordCountService wordCountService,
            CharacterService characterService,
            LocationService locationService,
            ItemService itemService,
            ScenePlanningCompletenessService planningCompletenessService,
            SceneVersionService sceneVersionService,
            SceneDeletionLedgerService sceneDeletionLedgerService,
            WordCountEventService wordCountEventService,
            BookWordCountEventRepository wordCountEventRepository,
            BookService bookService,
            CurrentUserProvider currentUserProvider
    ) {
        this.sceneRepository = sceneRepository;
        this.chapterService = chapterService;
        this.wordCountService = wordCountService;
        this.characterService = characterService;
        this.locationService = locationService;
        this.itemService = itemService;
        this.planningCompletenessService = planningCompletenessService;
        this.sceneVersionService = sceneVersionService;
        this.sceneDeletionLedgerService = sceneDeletionLedgerService;
        this.wordCountEventService = wordCountEventService;
        this.wordCountEventRepository = wordCountEventRepository;
        this.bookService = bookService;
        this.currentUserProvider = currentUserProvider;
    }

    @Transactional(readOnly = true)
    public SceneResponse findById(UUID sceneId) {
        return SceneResponse.fromEntity(getScene(sceneId));
    }

    @Transactional
    public SceneResponse create(UUID chapterId, SceneRequest request) {
        Chapter chapter = chapterService.getChapter(chapterId);
        UUID bookId = chapter.getBook().getId();
        Book lockedBook = bookService.getBookForWordCountUpdate(bookId);
        UUID operationId = request.operationId() == null ? UUID.randomUUID() : request.operationId();
        String requestFingerprint = WordCountRequestFingerprint.sceneCreate(
                currentUserProvider.userId(),
                bookId,
                chapterId,
                request.title(),
                request.summary(),
                request.status(),
                request.sortOrder(),
                request.contentJson(),
                request.contentText()
        );
        SceneResponse idempotentCreateResponse = idempotentCreateRetryResponse(bookId, operationId, requestFingerprint);
        if (idempotentCreateResponse != null) {
            return idempotentCreateResponse;
        }

        int totalBefore = Math.toIntExact(sceneRepository.sumWordCountByBookId(bookId));
        int newWordCount = wordCountService.countWords(request.contentText());

        Scene scene = new Scene();
        scene.setBook(lockedBook);
        scene.setChapter(chapter);
        scene.setTitle(request.title());
        scene.setSummary(request.summary());
        scene.setStatus(request.status() == null ? SceneStatus.IDEA : request.status());
        scene.setSortOrder(request.sortOrder() == null ? sceneRepository.countByChapterId(chapterId) : request.sortOrder());
        scene.setContentJson(request.contentJson());
        scene.setContentText(request.contentText());
        scene.setWordCount(newWordCount);
        scene.setContentRevision(0L);
        if (scene.getStatus() == SceneStatus.PLANNED) {
            rejectIncompletePlanning(scene);
        }

        Scene savedScene = sceneRepository.save(scene);
        if (newWordCount > 0 || request.operationId() != null) {
            recordFreshEvent(lockedBook, new WordCountEventCommand(
                    bookId,
                    savedScene.getId(),
                    savedScene.getId(),
                    savedScene.getTitle(),
                    BookWordCountEventType.CONTENT_SAVE,
                    newWordCount,
                    newWordCount,
                    operationId,
                    operationId,
                    null,
                    savedScene.getContentRevision(),
                    requestFingerprint,
                    totalBefore + newWordCount
            ));
        }

        return SceneResponse.fromEntity(savedScene);
    }

    @Transactional
    public SceneResponse update(UUID sceneId, SceneUpdateRequest request) {
        Scene scene = getScene(sceneId);
        RequestValidation.rejectBlankWhenPresent("title", request.title());

        if (request.title() != null) {
            scene.setTitle(request.title());
        }
        if (request.summary() != null) {
            scene.setSummary(request.summary());
        }
        if (request.status() != null) {
            boolean enteringPlanned = scene.getStatus() != SceneStatus.PLANNED && request.status() == SceneStatus.PLANNED;
            if (enteringPlanned) {
                rejectIncompletePlanning(scene);
            }
            scene.setStatus(request.status());
        }
        if (request.sortOrder() != null) {
            scene.setSortOrder(request.sortOrder());
        }

        return SceneResponse.fromEntity(scene);
    }

    @Transactional
    public SceneResponse updateContent(UUID sceneId, SceneContentRequest request) {
        Scene scene = getSceneForUpdate(sceneId);
        rejectMissingOperationId(request.operationId());
        Book lockedBook = bookService.getBookForWordCountUpdate(scene.getBook().getId());
        SceneVersionSource source = contentSource(request.source());
        String requestFingerprint = WordCountRequestFingerprint.contentSave(
                currentUserProvider.userId(),
                lockedBook.getId(),
                scene.getId(),
                request.expectedContentRevision(),
                source,
                request.contentJson(),
                request.contentText()
        );
        SceneResponse idempotentRetryResponse = idempotentRetryResponse(scene, request.operationId(), requestFingerprint);
        if (idempotentRetryResponse != null) {
            return idempotentRetryResponse;
        }
        rejectStaleContentRevision(scene, request.expectedContentRevision());
        if (sameContent(scene, request.contentJson(), request.contentText())) {
            return SceneResponse.fromEntity(scene);
        }

        UUID bookId = lockedBook.getId();
        int totalBefore = Math.toIntExact(sceneRepository.sumWordCountByBookId(bookId));
        int oldWordCount = wordCount(scene);
        int newWordCount = wordCountService.countWords(request.contentText());
        int wordCountDelta = newWordCount - oldWordCount;
        long revisionBefore = scene.getContentRevision();

        sceneVersionService.checkpointBeforeContentOverwrite(scene, source);
        scene.setContentJson(request.contentJson());
        scene.setContentText(request.contentText());
        scene.setWordCount(newWordCount);
        scene.incrementContentRevision();
        if (source == SceneVersionSource.MANUAL_SAVE) {
            sceneVersionService.checkpointAfterManualContentSave(scene);
        }
        recordFreshEvent(lockedBook, new WordCountEventCommand(
                bookId,
                scene.getId(),
                scene.getId(),
                scene.getTitle(),
                BookWordCountEventType.CONTENT_SAVE,
                wordCountDelta,
                wordCountDelta,
                request.operationId(),
                request.operationId(),
                revisionBefore,
                scene.getContentRevision(),
                requestFingerprint,
                totalBefore + wordCountDelta
        ));

        return SceneResponse.fromEntity(scene);
    }

    @Transactional
    public SceneResponse restoreVersion(UUID sceneId, UUID versionId, SceneVersionRestoreRequest request) {
        Scene scene = getSceneForUpdate(sceneId);
        rejectMissingOperationId(request.operationId());
        Book lockedBook = bookService.getBookForWordCountUpdate(scene.getBook().getId());
        SceneVersion version = sceneVersionService.getCurrentSceneVersion(sceneId, versionId);
        String requestFingerprint = WordCountRequestFingerprint.versionRestore(
                currentUserProvider.userId(),
                lockedBook.getId(),
                scene.getId(),
                versionId,
                request.expectedContentRevision()
        );
        SceneResponse idempotentRetryResponse = idempotentRestoreRetryResponse(scene, request.operationId(), requestFingerprint);
        if (idempotentRetryResponse != null) {
            return idempotentRetryResponse;
        }
        rejectStaleContentRevision(scene, request.expectedContentRevision());

        if (sameContent(scene, version.getContentJson(), version.getContentText())) {
            return SceneResponse.fromEntity(scene);
        }

        UUID bookId = lockedBook.getId();
        int totalBefore = Math.toIntExact(sceneRepository.sumWordCountByBookId(bookId));
        int previousWordCount = wordCount(scene);
        int newWordCount = wordCountService.countWords(version.getContentText());
        int manuscriptWordDelta = newWordCount - previousWordCount;
        long revisionBefore = scene.getContentRevision();

        sceneVersionService.checkpointBeforeRestore(scene);
        scene.setContentJson(version.getContentJson());
        scene.setContentText(version.getContentText());
        scene.setWordCount(newWordCount);
        scene.incrementContentRevision();
        recordFreshEvent(lockedBook, new WordCountEventCommand(
                bookId,
                scene.getId(),
                scene.getId(),
                scene.getTitle(),
                BookWordCountEventType.VERSION_RESTORE,
                0,
                manuscriptWordDelta,
                request.operationId(),
                request.operationId(),
                revisionBefore,
                scene.getContentRevision(),
                requestFingerprint,
                totalBefore + manuscriptWordDelta
        ));

        return SceneResponse.fromEntity(scene);
    }

    @Transactional
    public SceneResponse updatePlanning(UUID sceneId, ScenePlanningRequest request) {
        Scene scene = getScene(sceneId);
        UUID bookId = scene.getBook().getId();
        List<String> gapsBefore = scene.getStatus() == SceneStatus.PLANNED
                ? planningCompletenessService.planningGaps(scene)
                : List.of();
        Character povCharacter = findCharacterForBook(bookId, request.povCharacterId(), "povCharacterId");
        Location mainLocation = findLocationForBook(bookId, request.mainLocationId());
        Set<Character> participantCharacters = findParticipantsForBook(bookId, request.participantCharacterIds());
        Set<Item> items = findItemsForBook(bookId, request.itemIds());

        scene.setGoal(request.goal());
        scene.setConflict(request.conflict());
        scene.setOutcome(request.outcome());
        scene.setPlanningNotes(request.planningNotes());
        scene.setPovCharacter(povCharacter);
        scene.setMainLocation(mainLocation);
        scene.setParticipantCharacters(participantCharacters);
        scene.setItems(items);
        if (scene.getStatus() == SceneStatus.PLANNED) {
            rejectIntroducedPlanningGaps(scene, gapsBefore);
        }

        return SceneResponse.fromEntity(scene);
    }

    @Transactional
    public void delete(UUID sceneId) {
        Scene scene = getSceneForUpdate(sceneId);
        Book lockedBook = bookService.getBookForWordCountUpdate(scene.getBook().getId());
        sceneDeletionLedgerService.prepareSceneDelete(scene, lockedBook);
        sceneRepository.delete(scene);
    }

    @Transactional
    public void reorder(UUID chapterId, ReorderRequest request) {
        chapterService.getChapter(chapterId);
        List<Scene> scenes = sceneRepository.findByChapterIdOrderBySortOrderAsc(chapterId);
        applyReorder(scenes, request.orderedIds(), Scene::getId, Scene::setSortOrder, "scenes");
    }

    @Transactional(readOnly = true)
    public Scene getScene(UUID sceneId) {
        return sceneRepository.findByIdAndTenantId(sceneId, currentUserProvider.tenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Scene not found: " + sceneId));
    }

    private Scene getSceneForUpdate(UUID sceneId) {
        return sceneRepository.findByIdAndTenantIdForUpdate(sceneId, currentUserProvider.tenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Scene not found: " + sceneId));
    }

    private Character findCharacterForBook(UUID bookId, UUID characterId, String fieldName) {
        if (characterId == null) {
            return null;
        }

        Character character = characterService.getCharacter(characterId);
        if (!character.getBook().getId().equals(bookId)) {
            throw new BadRequestException(fieldName + " must belong to the same book as the scene");
        }

        return character;
    }

    private Location findLocationForBook(UUID bookId, UUID locationId) {
        if (locationId == null) {
            return null;
        }

        Location location = locationService.getLocation(locationId);
        if (!location.getBook().getId().equals(bookId)) {
            throw new BadRequestException("mainLocationId must belong to the same book as the scene");
        }

        return location;
    }

    private Item findItemForBook(UUID bookId, UUID itemId) {
        Item item = itemService.getItem(itemId);
        if (!item.getBook().getId().equals(bookId)) {
            throw new BadRequestException("itemIds must belong to the same book as the scene");
        }

        return item;
    }

    private Set<Character> findParticipantsForBook(UUID bookId, List<UUID> participantCharacterIds) {
        rejectNullIds("participantCharacterIds", participantCharacterIds);
        rejectDuplicateIds("participantCharacterIds", participantCharacterIds);

        return participantCharacterIds.stream()
                .map(characterId -> findCharacterForBook(bookId, characterId, "participantCharacterIds"))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<Item> findItemsForBook(UUID bookId, List<UUID> itemIds) {
        rejectNullIds("itemIds", itemIds);
        rejectDuplicateIds("itemIds", itemIds);

        return itemIds.stream()
                .map(itemId -> findItemForBook(bookId, itemId))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private void rejectDuplicateIds(String fieldName, List<UUID> ids) {
        if (ids.size() != new HashSet<>(ids).size()) {
            throw new BadRequestException(fieldName + " must not contain duplicate IDs");
        }
    }

    private void rejectNullIds(String fieldName, List<UUID> ids) {
        if (ids.stream().anyMatch(id -> id == null)) {
            throw new BadRequestException(fieldName + " must not contain null IDs");
        }
    }

    private <T> void applyReorder(
            List<T> children,
            List<UUID> orderedIds,
            Function<T, UUID> idGetter,
            OrderSetter<T> orderSetter,
            String childName
    ) {
        if (orderedIds.size() != new HashSet<>(orderedIds).size()) {
            throw new BadRequestException("Duplicate IDs are not allowed");
        }
        if (orderedIds.size() != children.size()) {
            throw new BadRequestException("Reorder list must include all " + childName + " for the parent");
        }

        Map<UUID, T> childrenById = children.stream()
                .collect(Collectors.toMap(idGetter, Function.identity()));

        if (!childrenById.keySet().equals(new HashSet<>(orderedIds))) {
            throw new BadRequestException("All IDs must exist and belong to the parent");
        }

        for (int index = 0; index < orderedIds.size(); index++) {
            T child = childrenById.get(orderedIds.get(index));
            orderSetter.setSortOrder(child, index);
        }
    }

    private int wordCount(Scene scene) {
        return scene.getWordCount() == null ? 0 : scene.getWordCount();
    }

    private void rejectStaleContentRevision(Scene scene, Long expectedContentRevision) {
        if (expectedContentRevision == null) {
            throw new BadRequestException("expectedContentRevision is required");
        }
        if (!expectedContentRevision.equals(scene.getContentRevision())) {
            throw new ConflictException("Scene content has changed. Reload the scene before saving.");
        }
    }

    private void rejectMissingOperationId(UUID operationId) {
        if (operationId == null) {
            throw new BadRequestException("operationId is required");
        }
    }

    private SceneVersionSource contentSource(SceneVersionSource source) {
        if (source == null) {
            return SceneVersionSource.AUTO_SAVE;
        }
        if (source != SceneVersionSource.AUTO_SAVE && source != SceneVersionSource.MANUAL_SAVE) {
            throw new BadRequestException("source must be AUTO_SAVE or MANUAL_SAVE");
        }
        return source;
    }

    private boolean sameContent(Scene scene, String contentJson, String contentText) {
        return normalized(scene.getContentJson()).equals(normalized(contentJson))
                && normalized(scene.getContentText()).equals(normalized(contentText));
    }

    private SceneResponse idempotentCreateRetryResponse(UUID bookId, UUID idempotencyKey, String requestFingerprint) {
        return wordCountEventRepository.findByBookIdAndIdempotencyKey(bookId, idempotencyKey)
                .map(event -> {
                    requireMatchingFingerprint(
                            event,
                            requestFingerprint,
                            "Idempotency key was already used for a different scene creation."
                    );
                    if (event.getScene() == null) {
                        throw new ResourceNotFoundException("Scene not found for idempotent create retry.");
                    }
                    return SceneResponse.fromEntity(event.getScene());
                })
                .orElse(null);
    }

    private SceneResponse idempotentRetryResponse(Scene scene, UUID idempotencyKey, String requestFingerprint) {
        return wordCountEventRepository.findByBookIdAndIdempotencyKey(scene.getBook().getId(), idempotencyKey)
                .map(event -> {
                    requireMatchingFingerprint(
                            event,
                            requestFingerprint,
                            "Idempotency key was already used for a different scene content update."
                    );
                    return SceneResponse.fromEntity(scene);
                })
                .orElse(null);
    }

    private SceneResponse idempotentRestoreRetryResponse(
            Scene scene,
            UUID idempotencyKey,
            String requestFingerprint
    ) {
        return wordCountEventRepository.findByBookIdAndIdempotencyKey(scene.getBook().getId(), idempotencyKey)
                .map(event -> {
                    requireMatchingFingerprint(
                            event,
                            requestFingerprint,
                            "Idempotency key was already used for a different scene version restore."
                    );
                    return SceneResponse.fromEntity(scene);
                })
                .orElse(null);
    }

    private void requireMatchingFingerprint(
            BookWordCountEvent event,
            String requestFingerprint,
            String conflictMessage
    ) {
        if (event.getRequestFingerprint() == null) {
            throw new WordCountEventConflictException(
                    "Legacy idempotency event cannot prove a matching retry. Please reload and retry with a new operation."
            );
        }
        if (!event.getRequestFingerprint().equals(requestFingerprint)) {
            throw new WordCountEventConflictException(conflictMessage);
        }
    }

    private void recordFreshEvent(Book lockedBook, WordCountEventCommand command) {
        WordCountEventRecordResult result = wordCountEventService.recordForLockedBook(lockedBook, command);
        if (result == WordCountEventRecordResult.ALREADY_RECORDED) {
            throw new WordCountEventConflictException("Idempotency key was already used for a different word-count event.");
        }
    }

    private String normalized(String value) {
        return value == null ? "" : value;
    }

    private void rejectIncompletePlanning(Scene scene) {
        if (!planningCompletenessService.isComplete(scene)) {
            throw new BadRequestException(
                    "Scene status PLANNED requires complete planning. Missing fields: "
                            + planningCompletenessService.formatMissingPlanningFields(scene)
            );
        }
    }

    private void rejectIntroducedPlanningGaps(Scene scene, List<String> gapsBefore) {
        List<String> gapsAfter = planningCompletenessService.planningGaps(scene);
        if (gapsAfter.stream().anyMatch(gap -> !gapsBefore.contains(gap))) {
            throw new BadRequestException(
                    "Scene status PLANNED cannot lose required planning fields. Missing fields: "
                            + planningCompletenessService.formatMissingPlanningFields(scene)
            );
        }
    }

    @FunctionalInterface
    private interface OrderSetter<T> {
        void setSortOrder(T child, int sortOrder);
    }
}
