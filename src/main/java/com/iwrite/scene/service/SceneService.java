package com.iwrite.scene.service;

import com.iwrite.chapter.entity.Chapter;
import com.iwrite.chapter.service.ChapterService;
import com.iwrite.common.dto.ReorderRequest;
import com.iwrite.common.exception.BadRequestException;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.common.validation.RequestValidation;
import com.iwrite.common.wordcount.WordCountService;
import com.iwrite.scene.dto.SceneContentRequest;
import com.iwrite.scene.dto.SceneRequest;
import com.iwrite.scene.dto.SceneResponse;
import com.iwrite.scene.dto.SceneUpdateRequest;
import com.iwrite.scene.entity.Scene;
import com.iwrite.scene.entity.SceneStatus;
import com.iwrite.scene.repository.SceneRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SceneService {

    private final SceneRepository sceneRepository;
    private final ChapterService chapterService;
    private final WordCountService wordCountService;

    public SceneService(SceneRepository sceneRepository, ChapterService chapterService, WordCountService wordCountService) {
        this.sceneRepository = sceneRepository;
        this.chapterService = chapterService;
        this.wordCountService = wordCountService;
    }

    @Transactional(readOnly = true)
    public SceneResponse findById(UUID sceneId) {
        return SceneResponse.fromEntity(getScene(sceneId));
    }

    @Transactional
    public SceneResponse create(UUID chapterId, SceneRequest request) {
        Chapter chapter = chapterService.getChapter(chapterId);

        Scene scene = new Scene();
        scene.setBook(chapter.getBook());
        scene.setChapter(chapter);
        scene.setTitle(request.title());
        scene.setSummary(request.summary());
        scene.setStatus(request.status() == null ? SceneStatus.IDEA : request.status());
        scene.setSortOrder(request.sortOrder() == null ? sceneRepository.countByChapterId(chapterId) : request.sortOrder());
        scene.setContentJson(request.contentJson());
        scene.setContentText(request.contentText());
        scene.setWordCount(wordCountService.countWords(request.contentText()));

        return SceneResponse.fromEntity(sceneRepository.save(scene));
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
        scene.setContentJson(request.contentJson());
        scene.setContentText(request.contentText());
        scene.setWordCount(wordCountService.countWords(request.contentText()));

        return SceneResponse.fromEntity(scene);
    }

    @Transactional
    public void delete(UUID sceneId) {
        Scene scene = getScene(sceneId);
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
        return sceneRepository.findById(sceneId)
                .orElseThrow(() -> new ResourceNotFoundException("Scene not found: " + sceneId));
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

    @FunctionalInterface
    private interface OrderSetter<T> {
        void setSortOrder(T child, int sortOrder);
    }
}
