package com.app.campusagent.lostfound.service;

import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.domain.LostFoundAuditAction;
import com.app.campusagent.lostfound.domain.LostFoundAuditLog;
import com.app.campusagent.lostfound.repository.LostFoundAuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 报告级写操作审计：与业务写操作处于同一事务，保证可追溯性。 */
@Service
public class LostFoundAuditService {

    private final LostFoundAuditLogRepository auditLogRepository;

    public LostFoundAuditService(LostFoundAuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public void record(
            LostFoundAuditAction action,
            Long reportId,
            String itemName,
            User actor,
            String reason,
            String detail) {
        auditLogRepository.save(new LostFoundAuditLog(
                action,
                reportId,
                itemName,
                actor,
                reason,
                detail));
    }
}
