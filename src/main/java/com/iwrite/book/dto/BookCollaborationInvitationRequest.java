package com.iwrite.book.dto;

import java.time.OffsetDateTime;

public record BookCollaborationInvitationRequest(
        String recipientEmail,
        String requestedRole,
        OffsetDateTime expiresAt
) {
}
