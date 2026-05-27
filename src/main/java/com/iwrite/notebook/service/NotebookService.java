package com.iwrite.notebook.service;

import com.iwrite.book.entity.Book;
import com.iwrite.book.service.BookService;
import com.iwrite.common.exception.BadRequestException;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.common.validation.RequestValidation;
import com.iwrite.notebook.dto.NotebookCategoryRequest;
import com.iwrite.notebook.dto.NotebookCategoryResponse;
import com.iwrite.notebook.dto.NotebookCategoryUpdateRequest;
import com.iwrite.notebook.dto.NotebookNoteRequest;
import com.iwrite.notebook.dto.NotebookNoteResponse;
import com.iwrite.notebook.dto.NotebookNoteUpdateRequest;
import com.iwrite.notebook.entity.NotebookCategory;
import com.iwrite.notebook.entity.NotebookNote;
import com.iwrite.notebook.entity.NotebookNoteStatus;
import com.iwrite.notebook.repository.NotebookCategoryRepository;
import com.iwrite.notebook.repository.NotebookNoteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class NotebookService {

    private static final List<String> DEFAULT_CATEGORY_NAMES = List.of(
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
    private final BookService bookService;

    public NotebookService(
            NotebookCategoryRepository categoryRepository,
            NotebookNoteRepository noteRepository,
            BookService bookService
    ) {
        this.categoryRepository = categoryRepository;
        this.noteRepository = noteRepository;
        this.bookService = bookService;
    }

    @Transactional
    public List<NotebookCategoryResponse> findCategoriesByBook(UUID bookId) {
        Book book = bookService.getBook(bookId);
        ensureDefaultCategories(book);
        return categoryRepository.findByBookIdOrderBySortOrderAscNameAscIdAsc(bookId)
                .stream()
                .map(NotebookCategoryResponse::fromEntity)
                .toList();
    }

    @Transactional
    public NotebookCategoryResponse createCategory(UUID bookId, NotebookCategoryRequest request) {
        Book book = bookService.getBook(bookId);
        rejectDuplicateCategoryName(bookId, request.name());

        NotebookCategory category = new NotebookCategory();
        category.setBook(book);
        category.setName(request.name());
        category.setSortOrder(request.sortOrder() == null ? nextSortOrder(bookId) : request.sortOrder());
        category.setDefault(false);

        return NotebookCategoryResponse.fromEntity(categoryRepository.save(category));
    }

    @Transactional
    public NotebookCategoryResponse updateCategory(UUID categoryId, NotebookCategoryUpdateRequest request) {
        NotebookCategory category = getCategory(categoryId);
        RequestValidation.rejectBlankWhenPresent("name", request.name());

        if (request.name() != null && !category.getName().equalsIgnoreCase(request.name())) {
            rejectDuplicateCategoryName(category.getBook().getId(), request.name());
            category.setName(request.name());
        }
        if (request.sortOrder() != null) {
            category.setSortOrder(request.sortOrder());
        }

        return NotebookCategoryResponse.fromEntity(category);
    }

    @Transactional
    public void deleteCategory(UUID categoryId) {
        NotebookCategory category = getCategory(categoryId);
        noteRepository.findByCategoryId(categoryId)
                .forEach(note -> note.setCategory(null));
        categoryRepository.delete(category);
        categoryRepository.flush();
    }

    @Transactional(readOnly = true)
    public List<NotebookNoteResponse> findNotesByBook(UUID bookId, UUID categoryId) {
        bookService.getBook(bookId);

        if (categoryId != null) {
            NotebookCategory category = getCategory(categoryId);
            if (!category.getBook().getId().equals(bookId)) {
                throw new BadRequestException("categoryId must belong to the same book");
            }
            return noteRepository.findByBookIdAndCategoryIdOrderByUpdatedAtDescIdAsc(bookId, categoryId)
                    .stream()
                    .map(NotebookNoteResponse::fromEntity)
                    .toList();
        }

        return noteRepository.findByBookIdOrderByUpdatedAtDescIdAsc(bookId)
                .stream()
                .map(NotebookNoteResponse::fromEntity)
                .toList();
    }

    @Transactional
    public NotebookNoteResponse createNote(UUID bookId, NotebookNoteRequest request) {
        Book book = bookService.getBook(bookId);

        NotebookNote note = new NotebookNote();
        note.setBook(book);
        note.setTitle(request.title());
        note.setContent(request.content());
        note.setCategory(findCategoryForBook(bookId, request.categoryId()));
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
        RequestValidation.rejectBlankWhenPresent("title", request.title());

        if (request.title() != null) {
            note.setTitle(request.title());
        }
        if (request.content() != null) {
            note.setContent(request.content());
        }
        if (request.isCategoryIdPresent()) {
            note.setCategory(findCategoryForBook(note.getBook().getId(), request.categoryId()));
        }
        if (request.status() != null) {
            note.setStatus(request.status());
        }

        return NotebookNoteResponse.fromEntity(note);
    }

    @Transactional
    public void deleteNote(UUID noteId) {
        NotebookNote note = getNote(noteId);
        noteRepository.delete(note);
    }

    @Transactional(readOnly = true)
    public NotebookCategory getCategory(UUID categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Notebook category not found: " + categoryId));
    }

    @Transactional(readOnly = true)
    public NotebookNote getNote(UUID noteId) {
        return noteRepository.findById(noteId)
                .orElseThrow(() -> new ResourceNotFoundException("Notebook note not found: " + noteId));
    }

    private void ensureDefaultCategories(Book book) {
        UUID bookId = book.getId();
        int sortOrder = 0;
        for (String name : DEFAULT_CATEGORY_NAMES) {
            if (!categoryRepository.existsByBookIdAndNameIgnoreCase(bookId, name)) {
                NotebookCategory category = new NotebookCategory();
                category.setBook(book);
                category.setName(name);
                category.setSortOrder(sortOrder);
                category.setDefault(true);
                categoryRepository.save(category);
            }
            sortOrder++;
        }
        categoryRepository.flush();
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

    private int nextSortOrder(UUID bookId) {
        return categoryRepository.findByBookIdOrderBySortOrderAscNameAscIdAsc(bookId)
                .stream()
                .map(NotebookCategory::getSortOrder)
                .max(Integer::compareTo)
                .map(value -> value + 1)
                .orElse(0);
    }

    private void rejectDuplicateCategoryName(UUID bookId, String name) {
        if (categoryRepository.existsByBookIdAndNameIgnoreCase(bookId, name)) {
            throw new BadRequestException("Notebook category name must be unique within the book");
        }
    }
}
