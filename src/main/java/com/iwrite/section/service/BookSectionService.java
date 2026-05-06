package com.iwrite.section.service;

import com.iwrite.book.entity.Book;
import com.iwrite.book.service.BookService;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.common.validation.RequestValidation;
import com.iwrite.section.dto.BookSectionRequest;
import com.iwrite.section.dto.BookSectionResponse;
import com.iwrite.section.dto.BookSectionUpdateRequest;
import com.iwrite.section.entity.BookSection;
import com.iwrite.section.entity.SectionType;
import com.iwrite.section.repository.BookSectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class BookSectionService {

    private final BookSectionRepository sectionRepository;
    private final BookService bookService;

    public BookSectionService(BookSectionRepository sectionRepository, BookService bookService) {
        this.sectionRepository = sectionRepository;
        this.bookService = bookService;
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
        BookSection section = getSection(sectionId);
        sectionRepository.delete(section);
    }

    @Transactional(readOnly = true)
    public BookSection getSection(UUID sectionId) {
        return sectionRepository.findById(sectionId)
                .orElseThrow(() -> new ResourceNotFoundException("Section not found: " + sectionId));
    }
}
