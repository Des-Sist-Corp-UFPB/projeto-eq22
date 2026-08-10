package com.iwrite.audit.service;

import com.iwrite.audit.entity.AuditAction;
import com.iwrite.audit.entity.AuditLog;
import com.iwrite.audit.entity.AuditResourceType;
import com.iwrite.audit.entity.AuditResult;
import com.iwrite.audit.repository.AuditLogRepository;
import com.iwrite.user.context.CurrentUserProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final CurrentUserProvider currentUserProvider;

    public AuditLogService(
            AuditLogRepository auditLogRepository,
            CurrentUserProvider currentUserProvider
    ) {
        this.auditLogRepository = auditLogRepository;
        this.currentUserProvider = currentUserProvider;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditLog record(
            AuditAction action,
            AuditResourceType resourceType,
            UUID resourceId,
            AuditResult result
    ) {
        AuditLog auditLog = new AuditLog(
                currentUserProvider.tenantId(),
                currentUserProvider.userId(),
                action,
                resourceType,
                resourceId,
                result
        );
        return auditLogRepository.save(auditLog);
    }
}
