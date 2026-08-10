package com.iwrite.book.controller;

import com.iwrite.audit.annotation.AuditedOperation;
import com.iwrite.audit.entity.AuditAction;
import com.iwrite.audit.entity.AuditResourceType;
import com.iwrite.book.dto.AddBookCollaboratorRequest;
import com.iwrite.book.dto.BookCollaboratorResponse;
import com.iwrite.book.service.BookCollaboratorService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/books/{bookId}/collaborators")
public class BookCollaboratorController {

    private final BookCollaboratorService collaboratorService;

    public BookCollaboratorController(BookCollaboratorService collaboratorService) {
        this.collaboratorService = collaboratorService;
    }

    @GetMapping
    public List<BookCollaboratorResponse> list(@PathVariable UUID bookId) {
        return collaboratorService.list(bookId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @AuditedOperation(
            action = AuditAction.COLLABORATOR_ADDED,
            resourceType = AuditResourceType.BOOK_COLLABORATOR,
            resourceId = "#request.userId"
    )
    public BookCollaboratorResponse add(
            @PathVariable UUID bookId,
            @Valid @RequestBody AddBookCollaboratorRequest request
    ) {
        return collaboratorService.add(bookId, request.userId());
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @AuditedOperation(
            action = AuditAction.COLLABORATOR_REMOVED,
            resourceType = AuditResourceType.BOOK_COLLABORATOR,
            resourceId = "#userId"
    )
    public void remove(@PathVariable UUID bookId, @PathVariable UUID userId) {
        collaboratorService.remove(bookId, userId);
    }
}
