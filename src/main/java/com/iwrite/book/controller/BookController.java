package com.iwrite.book.controller;

import com.iwrite.book.dto.BookRequest;
import com.iwrite.book.dto.BookResponse;
import com.iwrite.book.dto.BookUpdateRequest;
import com.iwrite.book.service.BookService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/books")
public class BookController {

    private final BookService bookService;

    public BookController(BookService bookService) {
        this.bookService = bookService;
    }

    @GetMapping
    public List<BookResponse> findAll() {
        return bookService.findAll();
    }

    @GetMapping("/{bookId}")
    public BookResponse findById(@PathVariable UUID bookId) {
        return bookService.findById(bookId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookResponse create(@Valid @RequestBody BookRequest request) {
        return bookService.create(request);
    }

    @PatchMapping("/{bookId}")
    public BookResponse update(@PathVariable UUID bookId, @Valid @RequestBody BookUpdateRequest request) {
        return bookService.update(bookId, request);
    }

    @DeleteMapping("/{bookId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID bookId) {
        bookService.delete(bookId);
    }
}
