package com.iwrite.chapter.service;

import com.iwrite.book.entity.Book;
import com.iwrite.book.service.BookAccessService;
import com.iwrite.chapter.dto.ChapterRequest;
import com.iwrite.chapter.dto.ChapterResponse;
import com.iwrite.chapter.dto.ChapterUpdateRequest;
import com.iwrite.chapter.entity.Chapter;
import com.iwrite.chapter.repository.ChapterRepository;
import com.iwrite.common.dto.ReorderRequest;
import com.iwrite.common.exception.BadRequestException;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.common.validation.RequestValidation;
import com.iwrite.scene.repository.SceneRepository;
import com.iwrite.scene.service.SceneDeletionLedgerService;
import com.iwrite.section.entity.BookSection;
import com.iwrite.section.service.BookSectionService;
import com.iwrite.user.context.CurrentUserProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ChapterService {

    private final ChapterRepository chapterRepository;
    private final BookSectionService sectionService;
    private final SceneRepository sceneRepository;
    private final SceneDeletionLedgerService sceneDeletionLedgerService;
    private final BookAccessService bookAccessService;
    private final CurrentUserProvider currentUserProvider;

    public ChapterService(
            ChapterRepository chapterRepository,
            BookSectionService sectionService,
            SceneRepository sceneRepository,
            SceneDeletionLedgerService sceneDeletionLedgerService,
            BookAccessService bookAccessService,
            CurrentUserProvider currentUserProvider
    ) {
        this.chapterRepository = chapterRepository;
        this.sectionService = sectionService;
        this.sceneRepository = sceneRepository;
        this.sceneDeletionLedgerService = sceneDeletionLedgerService;
        this.bookAccessService = bookAccessService;
        this.currentUserProvider = currentUserProvider;
    }

    @Transactional
    public ChapterResponse create(UUID sectionId, ChapterRequest request) {
        BookSection section = sectionService.getSection(sectionId);

        Chapter chapter = new Chapter();
        chapter.setBook(section.getBook());
        chapter.setSection(section);
        chapter.setTitle(request.title());
        chapter.setSummary(request.summary());
        chapter.setSortOrder(request.sortOrder() == null ? chapterRepository.countBySectionId(sectionId) : request.sortOrder());

        return ChapterResponse.fromEntity(chapterRepository.save(chapter));
    }

    @Transactional
    public ChapterResponse update(UUID chapterId, ChapterUpdateRequest request) {
        Chapter chapter = getChapter(chapterId);
        RequestValidation.rejectBlankWhenPresent("title", request.title());

        if (request.title() != null) {
            chapter.setTitle(request.title());
        }
        if (request.summary() != null) {
            chapter.setSummary(request.summary());
        }
        if (request.sortOrder() != null) {
            chapter.setSortOrder(request.sortOrder());
        }

        return ChapterResponse.fromEntity(chapter);
    }

    @Transactional
    public void delete(UUID chapterId) {
        Chapter chapter = getChapter(chapterId);
        var scenes = sceneRepository.findByChapterIdForUpdate(chapterId);
        Book lockedBook = bookAccessService.requireBookEditAccessForUpdate(chapter.getBook().getId());
        sceneDeletionLedgerService.prepareSceneDeletes(scenes, lockedBook, UUID.randomUUID());
        chapterRepository.deleteById(chapterId);
    }

    @Transactional
    public void reorder(UUID sectionId, ReorderRequest request) {
        sectionService.getSection(sectionId);
        List<Chapter> chapters = chapterRepository.findBySectionIdOrderBySortOrderAsc(sectionId);
        applyReorder(chapters, request.orderedIds(), Chapter::getId, Chapter::setSortOrder, "chapters");
    }

    @Transactional(readOnly = true)
    public Chapter getChapter(UUID chapterId) {
        Chapter chapter = chapterRepository.findByIdAndTenantId(chapterId, currentUserProvider.tenantId())
                .orElseThrow(() -> chapterNotFound(chapterId));
        requireChapterBookEditAccess(chapter, chapterId);
        return chapter;
    }

    private void requireChapterBookEditAccess(Chapter chapter, UUID chapterId) {
        try {
            bookAccessService.requireBookEditAccess(chapter.getBook().getId());
        } catch (ResourceNotFoundException exception) {
            throw chapterNotFound(chapterId);
        }
    }

    private ResourceNotFoundException chapterNotFound(UUID chapterId) {
        return new ResourceNotFoundException("Chapter not found: " + chapterId);
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

    @FunctionalInterface
    private interface OrderSetter<T> {
        void setSortOrder(T child, int sortOrder);
    }
}
