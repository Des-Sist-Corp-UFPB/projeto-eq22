package com.iwrite.scene.service;

import com.iwrite.chapter.entity.Chapter;
import com.iwrite.chapter.service.ChapterService;
import com.iwrite.character.entity.Character;
import com.iwrite.character.service.CharacterService;
import com.iwrite.common.dto.ReorderRequest;
import com.iwrite.common.exception.BadRequestException;
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
import com.iwrite.writingprogress.service.DailyWritingProgressService;
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
    private final DailyWritingProgressService dailyWritingProgressService;

    public SceneService(
            SceneRepository sceneRepository,
            ChapterService chapterService,
            WordCountService wordCountService,
            CharacterService characterService,
            LocationService locationService,
            ItemService itemService,
            DailyWritingProgressService dailyWritingProgressService
    ) {
        this.sceneRepository = sceneRepository;
        this.chapterService = chapterService;
        this.wordCountService = wordCountService;
        this.characterService = characterService;
        this.locationService = locationService;
        this.itemService = itemService;
        this.dailyWritingProgressService = dailyWritingProgressService;
    }

    @Transactional(readOnly = true)
    public SceneResponse findById(UUID sceneId) {
        return SceneResponse.fromEntity(getScene(sceneId));
    }

    @Transactional
    public SceneResponse create(UUID chapterId, SceneRequest request) {
        Chapter chapter = chapterService.getChapter(chapterId);
        UUID bookId = chapter.getBook().getId();
        int totalBefore = Math.toIntExact(sceneRepository.sumWordCountByBookId(bookId));
        int newWordCount = wordCountService.countWords(request.contentText());

        Scene scene = new Scene();
        scene.setBook(chapter.getBook());
        scene.setChapter(chapter);
        scene.setTitle(request.title());
        scene.setSummary(request.summary());
        scene.setStatus(request.status() == null ? SceneStatus.IDEA : request.status());
        scene.setSortOrder(request.sortOrder() == null ? sceneRepository.countByChapterId(chapterId) : request.sortOrder());
        scene.setContentJson(request.contentJson());
        scene.setContentText(request.contentText());
        scene.setWordCount(newWordCount);

        Scene savedScene = sceneRepository.save(scene);
        dailyWritingProgressService.recordWordCountChange(bookId, totalBefore, totalBefore + newWordCount);

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
            scene.setStatus(request.status());
        }
        if (request.sortOrder() != null) {
            scene.setSortOrder(request.sortOrder());
        }

        return SceneResponse.fromEntity(scene);
    }

    @Transactional
    public SceneResponse updateContent(UUID sceneId, SceneContentRequest request) {
        Scene scene = getScene(sceneId);
        UUID bookId = scene.getBook().getId();
        int totalBefore = Math.toIntExact(sceneRepository.sumWordCountByBookId(bookId));
        int oldWordCount = wordCount(scene);
        int newWordCount = wordCountService.countWords(request.contentText());

        scene.setContentJson(request.contentJson());
        scene.setContentText(request.contentText());
        scene.setWordCount(newWordCount);
        dailyWritingProgressService.recordWordCountChange(bookId, totalBefore, totalBefore - oldWordCount + newWordCount);

        return SceneResponse.fromEntity(scene);
    }

    @Transactional
    public SceneResponse updatePlanning(UUID sceneId, ScenePlanningRequest request) {
        Scene scene = getScene(sceneId);
        UUID bookId = scene.getBook().getId();

        scene.setGoal(request.goal());
        scene.setConflict(request.conflict());
        scene.setOutcome(request.outcome());
        scene.setPlanningNotes(request.planningNotes());
        scene.setPovCharacter(findCharacterForBook(bookId, request.povCharacterId(), "povCharacterId"));
        scene.setMainLocation(findLocationForBook(bookId, request.mainLocationId()));
        scene.setParticipantCharacters(findParticipantsForBook(bookId, request.participantCharacterIds()));
        scene.setItems(findItemsForBook(bookId, request.itemIds()));

        return SceneResponse.fromEntity(scene);
    }

    @Transactional
    public void delete(UUID sceneId) {
        Scene scene = getScene(sceneId);
        UUID bookId = scene.getBook().getId();
        int totalBefore = Math.toIntExact(sceneRepository.sumWordCountByBookId(bookId));
        int totalAfter = totalBefore - wordCount(scene);
        sceneRepository.delete(scene);
        dailyWritingProgressService.recordWordCountChange(bookId, totalBefore, totalAfter);
    }

    @Transactional
    public void reorder(UUID chapterId, ReorderRequest request) {
        chapterService.getChapter(chapterId);
        List<Scene> scenes = sceneRepository.findByChapterIdOrderBySortOrderAsc(chapterId);
        applyReorder(scenes, request.orderedIds(), Scene::getId, Scene::setSortOrder, "scenes");
    }

    @Transactional(readOnly = true)
    public Scene getScene(UUID sceneId) {
        return sceneRepository.findById(sceneId)
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

        for (int index = 0; index < orderedIds.size(); index++) {
            T child = childrenById.get(orderedIds.get(index));
            if (child == null) {
                throw new BadRequestException("All IDs must exist and belong to the parent");
            }

            orderSetter.setSortOrder(child, index);
        }
    }

    private int wordCount(Scene scene) {
        return scene.getWordCount() == null ? 0 : scene.getWordCount();
    }

    @FunctionalInterface
    private interface OrderSetter<T> {
        void setSortOrder(T child, int sortOrder);
    }
}
