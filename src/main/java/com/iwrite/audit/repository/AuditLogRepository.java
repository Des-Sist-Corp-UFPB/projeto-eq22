package com.iwrite.audit.repository;

import com.iwrite.audit.entity.AuditAction;
import com.iwrite.audit.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    Optional<AuditLog> findTopByActionAndResourceIdOrderByOccurredAtDesc(
            AuditAction action,
            UUID resourceId
    );
}
