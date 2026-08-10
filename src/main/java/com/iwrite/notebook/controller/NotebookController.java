package com.iwrite.notebook.controller;

import com.iwrite.notebook.dto.NotebookCategoryRequest;
import com.iwrite.notebook.dto.NotebookCategoryResponse;
import com.iwrite.notebook.dto.NotebookCategoryUpdateRequest;
import com.iwrite.notebook.dto.NotebookNoteRequest;
import com.iwrite.notebook.dto.NotebookNoteResponse;
import com.iwrite.notebook.dto.NotebookNoteUpdateRequest;
import com.iwrite.notebook.service.NotebookService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class NotebookController {

    private final NotebookService notebookService;

    public NotebookController(NotebookService notebookService) {
        this.notebookService = notebookService;
    }

    @GetMapping("/books/{bookId}/notebook/categories")
    public List<NotebookCategoryResponse> findCategoriesByBook(@PathVariable UUID bookId) {
        return notebookService.findCategoriesByBook(bookId);
    }

    @PostMapping("/books/{bookId}/notebook/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public NotebookCategoryResponse createCategory(
            @PathVariable UUID bookId,
            @Valid @RequestBody NotebookCategoryRequest request
    ) {
        return notebookService.createCategory(bookId, request);
    }

    @PatchMapping("/notebook/categories/{categoryId}")
    public NotebookCategoryResponse updateCategory(
            @PathVariable UUID categoryId,
            @Valid @RequestBody NotebookCategoryUpdateRequest request
    ) {
        return notebookService.updateCategory(categoryId, request);
    }

    @DeleteMapping("/notebook/categories/{categoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCategory(@PathVariable UUID categoryId) {
        notebookService.deleteCategory(categoryId);
    }

    @GetMapping("/books/{bookId}/notebook/notes")
    public List<NotebookNoteResponse> findNotesByBook(
            @PathVariable UUID bookId,
            @RequestParam(required = false) UUID categoryId
    ) {
        return notebookService.findNotesByBook(bookId, categoryId);
    }

    @PostMapping("/books/{bookId}/notebook/notes")
    @ResponseStatus(HttpStatus.CREATED)
    public NotebookNoteResponse createNote(
            @PathVariable UUID bookId,
            @Valid @RequestBody NotebookNoteRequest request
    ) {
        return notebookService.createNote(bookId, request);
    }

    @GetMapping("/notebook/notes/{noteId}")
    public NotebookNoteResponse findNoteById(@PathVariable UUID noteId) {
        return notebookService.findNoteById(noteId);
    }

    @PatchMapping("/notebook/notes/{noteId}")
    public NotebookNoteResponse updateNote(
            @PathVariable UUID noteId,
            @Valid @RequestBody NotebookNoteUpdateRequest request
    ) {
        return notebookService.updateNote(noteId, request);
    }

    @DeleteMapping("/notebook/notes/{noteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteNote(@PathVariable UUID noteId) {
        notebookService.deleteNote(noteId);
    }
}
