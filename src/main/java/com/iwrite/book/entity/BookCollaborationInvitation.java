package com.iwrite.book.entity;

import com.iwrite.tenant.entity.Tenant;
import com.iwrite.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "book_collaboration_invitations",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_book_collaboration_invitations_token_hash",
                columnNames = "token_hash"
        )
)
public class BookCollaborationInvitation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "book_id", nullable = false)
    private Book book;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inviter_user_id", nullable = false)
    private User inviter;

    @Column(nullable = false, length = 320)
    private String recipientEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private BookCollaborationRole requestedRole;

    @Column(nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BookCollaborationInvitationStatus status;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime expiresAt;

    private OffsetDateTime acceptedAt;

    private OffsetDateTime revokedAt;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    private long version;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (status == null) {
            status = BookCollaborationInvitationStatus.PENDING;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    /**
     * Status as it should be observed at {@code now}: a stored PENDING row whose
     * expiration has passed is reported as EXPIRED without mutating the row.
     */
    public BookCollaborationInvitationStatus effectiveStatus(OffsetDateTime now) {
        if (status == BookCollaborationInvitationStatus.PENDING && !expiresAt.isAfter(now)) {
            return BookCollaborationInvitationStatus.EXPIRED;
        }
        return status;
    }

    public boolean isUsable(OffsetDateTime now) {
        return effectiveStatus(now) == BookCollaborationInvitationStatus.PENDING;
    }

    public void revoke(OffsetDateTime now) {
        requireUsable(now);
        status = BookCollaborationInvitationStatus.REVOKED;
        revokedAt = now;
    }

    public void markAccepted(OffsetDateTime now) {
        requireUsable(now);
        status = BookCollaborationInvitationStatus.ACCEPTED;
        acceptedAt = now;
    }

    /**
     * Persists the derived EXPIRED state for a stored PENDING row that has passed
     * its expiration, releasing the partial unique index slot for a replacement.
     */
    public void markExpired(OffsetDateTime now) {
        if (status != BookCollaborationInvitationStatus.PENDING
                || effectiveStatus(now) != BookCollaborationInvitationStatus.EXPIRED) {
            throw new IllegalStateException("Only a pending invitation past its expiration can be marked expired.");
        }
        status = BookCollaborationInvitationStatus.EXPIRED;
    }

    private void requireUsable(OffsetDateTime now) {
        if (!isUsable(now)) {
            throw new IllegalStateException("Invitation is not pending and usable.");
        }
    }

    public UUID getId() {
        return id;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public void setTenant(Tenant tenant) {
        this.tenant = tenant;
    }

    public Book getBook() {
        return book;
    }

    public void setBook(Book book) {
        this.book = book;
    }

    public User getInviter() {
        return inviter;
    }

    public void setInviter(User inviter) {
        this.inviter = inviter;
    }

    public String getRecipientEmail() {
        return recipientEmail;
    }

    public void setRecipientEmail(String recipientEmail) {
        this.recipientEmail = recipientEmail;
    }

    public BookCollaborationRole getRequestedRole() {
        return requestedRole;
    }

    public void setRequestedRole(BookCollaborationRole requestedRole) {
        this.requestedRole = requestedRole;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public BookCollaborationInvitationStatus getStatus() {
        return status;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(OffsetDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public OffsetDateTime getAcceptedAt() {
        return acceptedAt;
    }

    public OffsetDateTime getRevokedAt() {
        return revokedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
