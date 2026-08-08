package com.iwrite.book.dto;

/**
 * Creation-time result carrying the raw invitation token exactly once. The raw
 * token is never persisted and must not be logged; toString is redacted so the
 * value cannot leak through accidental logging.
 */
public record BookCollaborationInvitationCreationResult(
        BookCollaborationInvitationResponse invitation,
        String rawToken
) {

    @Override
    public String toString() {
        return "BookCollaborationInvitationCreationResult[invitation=" + invitation + ", rawToken=redacted]";
    }
}
