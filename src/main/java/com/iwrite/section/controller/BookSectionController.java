package com.iwrite.section.controller;

import com.iwrite.common.dto.ReorderRequest;
import com.iwrite.section.dto.BookSectionRequest;
import com.iwrite.section.dto.BookSectionResponse;
import com.iwrite.section.dto.BookSectionUpdateRequest;
import com.iwrite.section.service.BookSectionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api")
public class BookSectionController {

    private final BookSectionService sectionService;

    public BookSectionController(BookSectionService sectionService) {
        this.sectionService = sectionService;
    }

    @PostMapping("/books/{bookId}/sections")
    @ResponseStatus(HttpStatus.CREATED)
    public BookSectionResponse create(@PathVariable UUID bookId, @Valid @RequestBody BookSectionRequest request) {
        return sectionService.create(bookId, request);
    }

    @PatchMapping("/sections/{sectionId}")
    public BookSectionResponse update(@PathVariable UUID sectionId, @Valid @RequestBody BookSectionUpdateRequest request) {
        return sectionService.update(sectionId, request);
    }

    @PatchMapping("/books/{bookId}/sections/reorder")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reorder(@PathVariable UUID bookId, @Valid @RequestBody ReorderRequest request) {
        sectionService.reorder(bookId, request);
    }

    @DeleteMapping("/sections/{sectionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID sectionId) {
        sectionService.delete(sectionId);
    }
}
