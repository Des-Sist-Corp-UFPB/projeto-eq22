package com.iwrite.book.repository;

import com.iwrite.book.entity.BookCollaborationInvitation;
import com.iwrite.book.entity.BookCollaborationInvitationStatus;
import com.iwrite.book.entity.BookCollaborationRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookCollaborationInvitationRepository extends JpaRepository<BookCollaborationInvitation, UUID> {

    Optional<BookCollaborationInvitation> findByTokenHash(String tokenHash);

    Optional<BookCollaborationInvitation> findByIdAndBook_IdAndTenant_Id(UUID id, UUID bookId, UUID tenantId);

    List<BookCollaborationInvitation> findByTenant_IdAndBook_IdAndRecipientEmailAndRequestedRoleAndStatus(
            UUID tenantId,
            UUID bookId,
            String recipientEmail,
            BookCollaborationRole requestedRole,
            BookCollaborationInvitationStatus status
    );
}
