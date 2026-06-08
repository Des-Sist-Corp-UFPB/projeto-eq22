package com.iwrite.section.service;

import com.iwrite.book.entity.Book;
import com.iwrite.book.service.BookService;
import com.iwrite.common.dto.ReorderRequest;
import com.iwrite.common.exception.BadRequestException;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.common.validation.RequestValidation;
import com.iwrite.scene.repository.SceneRepository;
import com.iwrite.sceneversion.service.SceneVersionService;
import com.iwrite.section.dto.BookSectionRequest;
import com.iwrite.section.dto.BookSectionResponse;
import com.iwrite.section.dto.BookSectionUpdateRequest;
import com.iwrite.section.entity.BookSection;
import com.iwrite.section.entity.SectionType;
import com.iwrite.section.repository.BookSectionRepository;
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
    private final BookService bookService;
    private final SceneRepository sceneRepository;
    private final SceneVersionService sceneVersionService;

    public BookSectionService(
            BookSectionRepository sectionRepository,
            BookService bookService,
            SceneRepository sceneRepository,
            SceneVersionService sceneVersionService
    ) {
        this.sectionRepository = sectionRepository;
        this.bookService = bookService;
        this.sceneRepository = sceneRepository;
        this.sceneVersionService = sceneVersionService;
    }

    @Transactional
    public BookSectionResponse create(UUID bookId, BookSectionRequest request) {
        Book book = bookService.getBook(bookId);

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
        getSection(sectionId);
        sceneVersionService.checkpointBeforeDelete(sceneRepository.findBySectionIdForUpdate(sectionId));
        sectionRepository.deleteById(sectionId);
    }

    @Transactional
    public void reorder(UUID bookId, ReorderRequest request) {
        bookService.getBook(bookId);
        List<BookSection> sections = sectionRepository.findByBookIdOrderBySortOrderAsc(bookId);
        applyReorder(sections, request.orderedIds(), BookSection::getId, BookSection::setSortOrder, "sections");
    }

    @Transactional(readOnly = true)
    public BookSection getSection(UUID sectionId) {
        return sectionRepository.findById(sectionId)
                .orElseThrow(() -> new ResourceNotFoundException("Section not found: " + sectionId));
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
