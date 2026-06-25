package com.iwrite.notebook.service;

import com.iwrite.book.entity.Book;
import com.iwrite.book.service.BookAccessService;
import com.iwrite.common.exception.BadRequestException;
import com.iwrite.common.exception.ConflictException;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.common.validation.RequestValidation;
import com.iwrite.notebook.NotebookCategoryOrdering;
import com.iwrite.notebook.dto.NotebookCategoryRequest;
import com.iwrite.notebook.dto.NotebookCategoryResponse;
import com.iwrite.notebook.dto.NotebookCategoryUpdateRequest;
import com.iwrite.notebook.dto.NotebookNoteRequest;
import com.iwrite.notebook.dto.NotebookNoteResponse;
import com.iwrite.notebook.dto.NotebookNoteUpdateRequest;
import com.iwrite.notebook.entity.NotebookCategory;
import com.iwrite.notebook.entity.NotebookNote;
import com.iwrite.notebook.entity.NotebookNoteStatus;
import com.iwrite.notebook.repository.BookNotebookSettingsRepository;
import com.iwrite.notebook.repository.NotebookCategoryRepository;
import com.iwrite.notebook.repository.NotebookNoteRepository;
import com.iwrite.user.context.CurrentUserProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class NotebookService {

    private static final List<String> STARTER_CATEGORY_NAMES = List.of(
            "Ideia",
            "Pesquisa",
            "Mundo",
            "Revisão",
            "Pergunta",
            "Referência",
            "Trecho",
            "Outro"
    );

    private final NotebookCategoryRepository categoryRepository;
    private final NotebookNoteRepository noteRepository;
    private final BookNotebookSettingsRepository settingsRepository;
    private final BookAccessService bookAccessService;
    private final CurrentUserProvider currentUserProvider;

    public NotebookService(
            NotebookCategoryRepository categoryRepository,
            NotebookNoteRepository noteRepository,
            BookNotebookSettingsRepository settingsRepository,
            BookAccessService bookAccessService,
            CurrentUserProvider currentUserProvider
    ) {
        this.categoryRepository = categoryRepository;
        this.noteRepository = noteRepository;
        this.settingsRepository = settingsRepository;
        this.bookAccessService = bookAccessService;
        this.currentUserProvider = currentUserProvider;
    }

    @Transactional
    public List<NotebookCategoryResponse> findCategoriesByBook(UUID bookId) {
        Book book = bookAccessService.requireBookReadAccess(bookId);
        initializeStarterCategories(book);
        return orderedCategories(bookId, currentUserProvider.tenantId())
                .stream()
                .map(NotebookCategoryResponse::fromEntity)
                .toList();
    }

    @Transactional
    public NotebookCategoryResponse createCategory(UUID bookId, NotebookCategoryRequest request) {
        Book book = bookAccessService.requireBookEditAccess(bookId);
        initializeStarterCategories(book);
        UUID tenantId = currentUserProvider.tenantId();
        rejectDuplicateCategoryName(bookId, tenantId, request.name());

        NotebookCategory category = new NotebookCategory();
        category.setBook(book);
        category.setName(request.name());
        category.setSortOrder(request.sortOrder() == null ? nextSortOrder(bookId, tenantId) : request.sortOrder());

        return NotebookCategoryResponse.fromEntity(categoryRepository.save(category));
    }

    @Transactional
    public NotebookCategoryResponse updateCategory(UUID categoryId, NotebookCategoryUpdateRequest request) {
        NotebookCategory category = getCategory(categoryId);
        requireCategoryBookAccess(category, categoryId, true);
        RequestValidation.rejectBlankWhenPresent("name", request.name());

        if (request.name() != null && !category.getName().equals(request.name())) {
            if (!category.getName().equalsIgnoreCase(request.name())) {
                rejectDuplicateCategoryName(
                        category.getBook().getId(),
                        currentUserProvider.tenantId(),
                        request.name()
                );
            }
            category.setName(request.name());
        }
        if (request.sortOrder() != null) {
            category.setSortOrder(request.sortOrder());
        }

        return NotebookCategoryResponse.fromEntity(category);
    }

    @Transactional
    public void deleteCategory(UUID categoryId) {
        NotebookCategory category = getCategoryForUpdate(categoryId);
        UUID bookId = category.getBook().getId();
        if (noteRepository.existsByCategoryIdAndBookIdNot(categoryId, bookId)) {
            throw notebookCategoryReferencedConflict();
        }

        noteRepository.findByBook_IdAndCategory_Id(bookId, categoryId)
                .forEach(note -> note.setCategory(null));
        try {
            categoryRepository.delete(category);
            categoryRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw notebookCategoryReferencedConflict();
        }
    }

    @Transactional(readOnly = true)
    public List<NotebookNoteResponse> findNotesByBook(UUID bookId, UUID categoryId) {
        bookAccessService.requireBookReadAccess(bookId);
        UUID tenantId = currentUserProvider.tenantId();

        if (categoryId != null) {
            NotebookCategory category = getCategory(categoryId);
            if (!category.getBook().getId().equals(bookId)) {
                throw new BadRequestException("categoryId must belong to the same book");
            }
            return noteRepository.findByBook_IdAndBook_Tenant_IdAndCategory_IdOrderByUpdatedAtDescIdAsc(
                            bookId,
                            tenantId,
                            categoryId
                    )
                    .stream()
                    .map(NotebookNoteResponse::fromEntity)
                    .toList();
        }

        return noteRepository.findByBook_IdAndBook_Tenant_IdOrderByUpdatedAtDescIdAsc(bookId, tenantId)
                .stream()
                .map(NotebookNoteResponse::fromEntity)
                .toList();
    }

    @Transactional
    public NotebookNoteResponse createNote(UUID bookId, NotebookNoteRequest request) {
        Book book = bookAccessService.requireBookEditAccess(bookId);
        NotebookCategory category = findCategoryForBook(bookId, request.categoryId());

        NotebookNote note = new NotebookNote();
        note.setBook(book);
        note.setTitle(request.title());
        note.setContent(request.content());
        note.setCategory(category);
        note.setStatus(request.status() == null ? NotebookNoteStatus.OPEN : request.status());

        return NotebookNoteResponse.fromEntity(noteRepository.save(note));
    }

    @Transactional(readOnly = true)
    public NotebookNoteResponse findNoteById(UUID noteId) {
        return NotebookNoteResponse.fromEntity(getNote(noteId));
    }

    @Transactional
    public NotebookNoteResponse updateNote(UUID noteId, NotebookNoteUpdateRequest request) {
        NotebookNote note = getNote(noteId);
        requireNoteBookAccess(note, noteId, true);
        RequestValidation.rejectBlankWhenPresent("title", request.title());
        NotebookCategory category = request.isCategoryIdPresent()
                ? findCategoryForBook(note.getBook().getId(), request.categoryId())
                : note.getCategory();

        if (request.title() != null) {
            note.setTitle(request.title());
        }
        if (request.isContentPresent()) {
            note.setContent(request.content());
        }
        if (request.isCategoryIdPresent()) {
            note.setCategory(category);
        }
        if (request.status() != null) {
            note.setStatus(request.status());
        }

        return NotebookNoteResponse.fromEntity(note);
    }

    @Transactional
    public void deleteNote(UUID noteId) {
        NotebookNote note = getNote(noteId);
        requireNoteBookAccess(note, noteId, true);
        noteRepository.delete(note);
    }

    @Transactional(readOnly = true)
    public NotebookCategory getCategory(UUID categoryId) {
        NotebookCategory category = categoryRepository.findByIdAndBook_Tenant_Id(categoryId, currentUserProvider.tenantId())
                .orElseThrow(() -> categoryNotFound(categoryId));
        requireCategoryBookAccess(category, categoryId, false);
        return category;
    }

    @Transactional(readOnly = true)
    public NotebookNote getNote(UUID noteId) {
        NotebookNote note = noteRepository.findByIdAndBook_Tenant_Id(noteId, currentUserProvider.tenantId())
                .orElseThrow(() -> noteNotFound(noteId));
        requireNoteBookAccess(note, noteId, false);
        return note;
    }

    private void initializeStarterCategories(Book book) {
        UUID bookId = book.getId();
        int insertedSettingsRows = settingsRepository.insertInitializedIfMissing(bookId);
        if (insertedSettingsRows == 0) {
            return;
        }

        int sortOrder = 0;
        for (String name : STARTER_CATEGORY_NAMES) {
            categoryRepository.insertStarterCategoryIfMissing(UUID.randomUUID(), bookId, name, sortOrder);
            sortOrder++;
        }
    }

    private NotebookCategory findCategoryForBook(UUID bookId, UUID categoryId) {
        if (categoryId == null) {
            return null;
        }

        NotebookCategory category = getCategory(categoryId);
        if (!category.getBook().getId().equals(bookId)) {
            throw new BadRequestException("categoryId must belong to the same book");
        }

        return category;
    }

    private NotebookCategory getCategoryForUpdate(UUID categoryId) {
        NotebookCategory category = categoryRepository.findByIdAndBookTenantIdForUpdate(categoryId, currentUserProvider.tenantId())
                .orElseThrow(() -> categoryNotFound(categoryId));
        requireCategoryBookAccess(category, categoryId, true);
        return category;
    }

    private int nextSortOrder(UUID bookId, UUID tenantId) {
        return orderedCategories(bookId, tenantId)
                .stream()
                .map(NotebookCategory::getSortOrder)
                .max(Integer::compareTo)
                .map(value -> value + 1)
                .orElse(0);
    }

    private void rejectDuplicateCategoryName(UUID bookId, UUID tenantId, String name) {
        if (categoryRepository.existsByBook_IdAndBook_Tenant_IdAndNameIgnoreCase(bookId, tenantId, name)) {
            throw new BadRequestException("Notebook category name must be unique within the book");
        }
    }

    private List<NotebookCategory> orderedCategories(UUID bookId, UUID tenantId) {
        return NotebookCategoryOrdering.ordered(
                categoryRepository.findByBook_IdAndBook_Tenant_IdOrderBySortOrderAscNameAscIdAsc(bookId, tenantId));
    }

    private ConflictException notebookCategoryReferencedConflict() {
        return new ConflictException("Notebook category cannot be deleted while it is referenced.");
    }

    private void requireCategoryBookAccess(NotebookCategory category, UUID categoryId, boolean edit) {
        try {
            if (edit) {
                bookAccessService.requireBookEditAccess(category.getBook().getId());
            } else {
                bookAccessService.requireBookReadAccess(category.getBook().getId());
            }
        } catch (ResourceNotFoundException exception) {
            throw categoryNotFound(categoryId);
        }
    }

    private void requireNoteBookAccess(NotebookNote note, UUID noteId, boolean edit) {
        try {
            if (edit) {
                bookAccessService.requireBookEditAccess(note.getBook().getId());
            } else {
                bookAccessService.requireBookReadAccess(note.getBook().getId());
            }
        } catch (ResourceNotFoundException exception) {
            throw noteNotFound(noteId);
        }
    }

    private ResourceNotFoundException categoryNotFound(UUID categoryId) {
        return new ResourceNotFoundException("Notebook category not found: " + categoryId);
    }

    private ResourceNotFoundException noteNotFound(UUID noteId) {
        return new ResourceNotFoundException("Notebook note not found: " + noteId);
    }
}
