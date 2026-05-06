package com.iwrite.book.service;

import com.iwrite.book.dto.BookRequest;
import com.iwrite.book.dto.BookResponse;
import com.iwrite.book.dto.BookUpdateRequest;
import com.iwrite.book.entity.Book;
import com.iwrite.book.entity.BookStatus;
import com.iwrite.book.repository.BookRepository;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.common.validation.RequestValidation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class BookService {

    private final BookRepository bookRepository;

    public BookService(BookRepository bookRepository) {
        this.bookRepository = bookRepository;
    }

    @Transactional(readOnly = true)
    public List<BookResponse> findAll() {
        return bookRepository.findAll()
                .stream()
                .map(BookResponse::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public BookResponse findById(UUID bookId) {
        return BookResponse.fromEntity(getBook(bookId));
    }

    @Transactional
    public BookResponse create(BookRequest request) {
        Book book = new Book();
        book.setTitle(request.title());
        book.setSubtitle(request.subtitle());
        book.setDescription(request.description());
        book.setStatus(request.status() == null ? BookStatus.PLANNING : request.status());

        return BookResponse.fromEntity(bookRepository.save(book));
    }

    @Transactional
    public BookResponse update(UUID bookId, BookUpdateRequest request) {
        Book book = getBook(bookId);
        RequestValidation.rejectBlankWhenPresent("title", request.title());

        if (request.title() != null) {
            book.setTitle(request.title());
        }
        if (request.subtitle() != null) {
            book.setSubtitle(request.subtitle());
        }
        if (request.description() != null) {
            book.setDescription(request.description());
        }
        if (request.status() != null) {
            book.setStatus(request.status());
        }

        return BookResponse.fromEntity(book);
    }

    @Transactional
    public void delete(UUID bookId) {
        Book book = getBook(bookId);
        bookRepository.delete(book);
    }

    @Transactional(readOnly = true)
    public Book getBook(UUID bookId) {
        return bookRepository.findById(bookId)
                .orElseThrow(() -> new ResourceNotFoundException("Book not found: " + bookId));
    }
}
