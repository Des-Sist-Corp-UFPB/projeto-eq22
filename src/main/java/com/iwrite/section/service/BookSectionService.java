package com.iwrite.section.service;

import com.iwrite.book.entity.Book;
import com.iwrite.book.service.BookAccessService;
import com.iwrite.common.dto.ReorderRequest;
import com.iwrite.common.exception.BadRequestException;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.common.validation.RequestValidation;
import com.iwrite.scene.repository.SceneRepository;
import com.iwrite.scene.service.SceneDeletionLedgerService;
import com.iwrite.section.dto.BookSectionRequest;
import com.iwrite.section.dto.BookSectionResponse;
import com.iwrite.section.dto.BookSectionUpdateRequest;
import com.iwrite.section.entity.BookSection;
import com.iwrite.section.entity.SectionType;
import com.iwrite.section.repository.BookSectionRepository;
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
public class BookSectionService {

    private final BookSectionRepository sectionRepository;
    private final BookAccessService bookAccessService;
    private final SceneRepository sceneRepository;
    private final SceneDeletionLedgerService sceneDeletionLedgerService;
    private final CurrentUserProvider currentUserProvider;

    public BookSectionService(
            BookSectionRepository sectionRepository,
            BookAccessService bookAccessService,
            SceneRepository sceneRepository,
            SceneDeletionLedgerService sceneDeletionLedgerService,
            CurrentUserProvider currentUserProvider
    ) {
        this.sectionRepository = sectionRepository;
        this.bookAccessService = bookAccessService;
        this.sceneRepository = sceneRepository;
        this.sceneDeletionLedgerService = sceneDeletionLedgerService;
        this.currentUserProvider = currentUserProvider;
    }

    @Transactional
    public BookSectionResponse create(UUID bookId, BookSectionRequest request) {
        Book book = bookAccessService.requireBookEditAccess(bookId);

        BookSection section = new BookSection();
        section.setBook(book);
        section.setTitle(request.title());
        section.setType(request.type() == null ? SectionType.PART : request.type());
        section.setSortOrder(request.sortOrder() == null ? sectionRepository.countByBookId(bookId) : request.sortOrder());

        return BookSectionResponse.fromEntity(sectionRepository.save(section));
    }

    @Transactional
    public BookSectionResponse update(UUID sectionId, BookSectionUpdateRequest request) {
        BookSection section = getSection(sectionId);
        RequestValidation.rejectBlankWhenPresent("title", request.title());

        if (request.title() != null) {
            section.setTitle(request.title());
        }
        if (request.type() != null) {
            section.setType(request.type());
        }
        if (request.sortOrder() != null) {
            section.setSortOrder(request.sortOrder());
        }

        return BookSectionResponse.fromEntity(section);
    }

    @Transactional
    public void delete(UUID sectionId) {
        BookSection section = getSection(sectionId);
        var scenes = sceneRepository.findBySectionIdForUpdate(sectionId);
        Book lockedBook = bookAccessService.requireBookEditAccessForUpdate(section.getBook().getId());
        sceneDeletionLedgerService.prepareSceneDeletes(scenes, lockedBook, UUID.randomUUID());
        sectionRepository.deleteById(sectionId);
    }

    @Transactional
    public void reorder(UUID bookId, ReorderRequest request) {
        bookAccessService.requireBookEditAccess(bookId);
        List<BookSection> sections = sectionRepository.findByBookIdOrderBySortOrderAsc(bookId);
        applyReorder(sections, request.orderedIds(), BookSection::getId, BookSection::setSortOrder, "sections");
    }

    @Transactional(readOnly = true)
    public BookSection getSection(UUID sectionId) {
        BookSection section = sectionRepository.findByIdAndBook_Tenant_Id(sectionId, currentUserProvider.tenantId())
                .orElseThrow(() -> sectionNotFound(sectionId));
        requireSectionBookEditAccess(section, sectionId);
        return section;
    }

    private void requireSectionBookEditAccess(BookSection section, UUID sectionId) {
        try {
            bookAccessService.requireBookEditAccess(section.getBook().getId());
        } catch (ResourceNotFoundException exception) {
            throw sectionNotFound(sectionId);
        }
    }

    private ResourceNotFoundException sectionNotFound(UUID sectionId) {
        return new ResourceNotFoundException("Section not found: " + sectionId);
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
