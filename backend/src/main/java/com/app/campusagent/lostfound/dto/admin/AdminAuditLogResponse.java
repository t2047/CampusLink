package com.app.campusagent.lostfound.dto.admin;

import com.app.campusagent.lostfound.domain.LostFoundAuditAction;

import java.time.Instant;

public record AdminAuditLogResponse(
        Long id,
        Long reportId,
        String itemName,
        LostFoundAuditAction action,
        String actorEmail,
        String reason,
        String detail,
        Instant createdAt) {
}
