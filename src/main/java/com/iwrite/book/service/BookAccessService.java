package com.iwrite.book.service;

import com.iwrite.book.entity.Book;
import com.iwrite.book.entity.BookAccessLevel;
import com.iwrite.book.repository.BookRepository;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.user.context.CurrentUserMembershipService;
import com.iwrite.user.context.CurrentUserProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class BookAccessService {

    private final BookRepository bookRepository;
    private final CurrentUserProvider currentUserProvider;
    private final CurrentUserMembershipService currentUserMembershipService;

    public BookAccessService(
            BookRepository bookRepository,
            CurrentUserProvider currentUserProvider,
            CurrentUserMembershipService currentUserMembershipService
    ) {
        this.bookRepository = bookRepository;
        this.currentUserProvider = currentUserProvider;
        this.currentUserMembershipService = currentUserMembershipService;
    }

    @Transactional(readOnly = true)
    public Book requireBookReadAccess(UUID bookId) {
        return requireAccessibleBook(bookId);
    }

    @Transactional(readOnly = true)
    public Book requireBookEditAccess(UUID bookId) {
        return requireAccessibleBook(bookId);
    }

    @Transactional(readOnly = true)
    public Book requireBookOwnerAccess(UUID bookId) {
        UUID userId = currentUserMembershipService.requireCurrentUserMemberId();
        UUID tenantId = currentUserProvider.tenantId();
        return bookRepository.findOwnedByIdAndTenantIdAndUserId(bookId, tenantId, userId)
                .orElseThrow(() -> bookNotFound(bookId));
    }

    @Transactional
    public Book requireBookEditAccessForUpdate(UUID bookId) {
        UUID userId = currentUserMembershipService.requireCurrentUserMemberId();
        UUID tenantId = currentUserProvider.tenantId();
        return bookRepository.findAccessibleByIdAndTenantIdAndUserIdForUpdate(bookId, tenantId, userId)
                .orElseThrow(() -> bookNotFound(bookId));
    }

    @Transactional
    public Book requireBookOwnerAccessForUpdate(UUID bookId) {
        UUID userId = currentUserMembershipService.requireCurrentUserMemberId();
        UUID tenantId = currentUserProvider.tenantId();
        return bookRepository.findOwnedByIdAndTenantIdAndUserIdForUpdate(bookId, tenantId, userId)
                .orElseThrow(() -> bookNotFound(bookId));
    }

    @Transactional(readOnly = true)
    public BookAccessLevel requireAccessLevel(UUID bookId) {
        UUID userId = currentUserMembershipService.requireCurrentUserMemberId();
        UUID tenantId = currentUserProvider.tenantId();
        return bookRepository.findAccessLevel(bookId, tenantId, userId)
                .orElseThrow(() -> bookNotFound(bookId));
    }

    private Book requireAccessibleBook(UUID bookId) {
        UUID userId = currentUserMembershipService.requireCurrentUserMemberId();
        UUID tenantId = currentUserProvider.tenantId();
        return bookRepository.findAccessibleByIdAndTenantIdAndUserId(bookId, tenantId, userId)
                .orElseThrow(() -> bookNotFound(bookId));
    }

    private ResourceNotFoundException bookNotFound(UUID bookId) {
        return new ResourceNotFoundException("Book not found: " + bookId);
    }
}
