package com.iwrite.book.service;

/**
 * A freshly generated invitation token. {@code rawValue} exists only in memory
 * at creation time and must never be persisted or logged; only {@code hashValue}
 * is stored.
 */
public record InvitationToken(String rawValue, String hashValue) {

    @Override
    public String toString() {
        return "InvitationToken[redacted]";
    }
}
