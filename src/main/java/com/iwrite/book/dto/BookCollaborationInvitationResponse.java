package com.iwrite.book.dto;

import com.iwrite.book.entity.BookCollaborationInvitation;
import com.iwrite.book.entity.BookCollaborationInvitationStatus;
import com.iwrite.book.entity.BookCollaborationRole;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Safe representation of an invitation: carries neither the raw token nor the
 * token hash. {@code status} is the effective status, so a stored pending row
 * past its expiration is reported as EXPIRED.
 */
public record BookCollaborationInvitationResponse(
        UUID id,
        UUID bookId,
        UUID inviterUserId,
        String recipientEmail,
        BookCollaborationRole requestedRole,
        BookCollaborationInvitationStatus status,
        OffsetDateTime expiresAt,
        OffsetDateTime acceptedAt,
        OffsetDateTime revokedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static BookCollaborationInvitationResponse fromEntity(BookCollaborationInvitation invitation, OffsetDateTime now) {
        return new BookCollaborationInvitationResponse(
                invitation.getId(),
                invitation.getBook().getId(),
                invitation.getInviter().getId(),
                invitation.getRecipientEmail(),
                invitation.getRequestedRole(),
                invitation.effectiveStatus(now),
                invitation.getExpiresAt(),
                invitation.getAcceptedAt(),
                invitation.getRevokedAt(),
                invitation.getCreatedAt(),
                invitation.getUpdatedAt()
        );
    }
}
