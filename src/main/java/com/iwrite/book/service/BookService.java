package com.iwrite.book.service;

import com.iwrite.book.dto.BookRequest;
import com.iwrite.book.dto.BookResponse;
import com.iwrite.book.dto.BookUpdateRequest;
import com.iwrite.book.entity.Book;
import com.iwrite.book.entity.BookAccessLevel;
import com.iwrite.book.entity.BookStatus;
import com.iwrite.book.repository.BookRepository;
import com.iwrite.common.validation.RequestValidation;
import com.iwrite.tenant.repository.TenantRepository;
import com.iwrite.user.context.CurrentUserMembershipService;
import com.iwrite.user.context.CurrentUserProvider;
import com.iwrite.user.repository.UserRepository;
import com.iwrite.writingprogress.service.WritingScheduleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.util.List;
import java.util.UUID;

@Service
public class BookService {

    private final BookRepository bookRepository;
    private final WritingScheduleService writingScheduleService;
    private final TenantRepository tenantRepository;
    private final CurrentUserProvider currentUserProvider;
    private final CurrentUserMembershipService currentUserMembershipService;
    private final BookAccessService bookAccessService;
    private final UserRepository userRepository;

    public BookService(
            BookRepository bookRepository,
            WritingScheduleService writingScheduleService,
            TenantRepository tenantRepository,
            CurrentUserProvider currentUserProvider,
            CurrentUserMembershipService currentUserMembershipService,
            BookAccessService bookAccessService,
            UserRepository userRepository
    ) {
        this.bookRepository = bookRepository;
        this.writingScheduleService = writingScheduleService;
        this.tenantRepository = tenantRepository;
        this.currentUserProvider = currentUserProvider;
        this.currentUserMembershipService = currentUserMembershipService;
        this.bookAccessService = bookAccessService;
        this.userRepository = userRepository;
    }

    @Transactional
    public List<BookResponse> findAll() {
        UUID userId = currentUserMembershipService.requireCurrentUserMemberId();
        UUID tenantId = currentUserProvider.tenantId();
        return bookRepository.findAllAccessibleByTenantIdAndUserId(tenantId, userId)
                .stream()
                .map(book -> BookResponse.fromEntity(
                        book,
                        writingScheduleService.getActivePlannedWritingDays(book),
                        accessLevel(book, userId)
                ))
                .toList();
    }

    @Transactional
    public BookResponse findById(UUID bookId) {
        Book book = bookAccessService.requireBookReadAccess(bookId);
        return BookResponse.fromEntity(
                book,
                writingScheduleService.getActivePlannedWritingDays(book),
                accessLevel(book, currentUserProvider.userId())
        );
    }

    @Transactional
    public BookResponse create(BookRequest request) {
        UUID userId = currentUserMembershipService.requireCurrentUserMemberId();
        Book book = new Book();
        book.setTenant(tenantRepository.getReferenceById(currentUserProvider.tenantId()));
        book.setOwner(userRepository.getReferenceById(userId));
        book.setTitle(request.title());
        book.setSubtitle(request.subtitle());
        book.setDescription(request.description());
        book.setStatus(request.status() == null ? BookStatus.PLANNING : request.status());
        book.setTargetWordCount(request.targetWordCount());
        book.setDailyTargetWordCount(request.dailyTargetWordCount());

        Book savedBook = bookRepository.save(book);
        List<DayOfWeek> plannedWritingDays = writingScheduleService.createInitialSchedule(savedBook, request.plannedWritingDays());
        return BookResponse.fromEntity(savedBook, plannedWritingDays, BookAccessLevel.OWNER);
    }

    @Transactional
    public BookResponse update(UUID bookId, BookUpdateRequest request) {
        Book book = bookAccessService.requireBookEditAccess(bookId);
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
        if (request.isTargetWordCountPresent()) {
            book.setTargetWordCount(request.targetWordCount());
        }
        if (request.isDailyTargetWordCountPresent()) {
            book.setDailyTargetWordCount(request.dailyTargetWordCount());
        }

        List<DayOfWeek> plannedWritingDays = request.isPlannedWritingDaysPresent()
                ? writingScheduleService.changeSchedule(book, request.plannedWritingDays())
                : writingScheduleService.getActivePlannedWritingDays(book);

        return BookResponse.fromEntity(book, plannedWritingDays, accessLevel(book, currentUserProvider.userId()));
    }

    @Transactional
    public void delete(UUID bookId) {
        Book book = bookAccessService.requireBookOwnerAccess(bookId);
        bookRepository.delete(book);
    }

    @Transactional(readOnly = true)
    public Book getBook(UUID bookId) {
        return bookAccessService.requireBookReadAccess(bookId);
    }

    @Transactional
    public Book getBookForWordCountUpdate(UUID bookId) {
        return bookAccessService.requireBookEditAccessForUpdate(bookId);
    }

    private BookAccessLevel accessLevel(Book book, UUID userId) {
        return book.getOwner().getId().equals(userId) ? BookAccessLevel.OWNER : BookAccessLevel.COLLABORATOR;
    }
}
