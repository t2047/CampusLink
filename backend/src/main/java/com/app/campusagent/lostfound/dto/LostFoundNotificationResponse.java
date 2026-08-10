package com.app.campusagent.lostfound.dto;

import com.app.campusagent.lostfound.domain.NotificationType;

import java.time.Instant;

public record LostFoundNotificationResponse(
        Long id,
        NotificationType type,
        Long reportId,
        Long claimId,
        String title,
        String message,
        boolean read,
        Instant createdAt,
        Instant readAt
) {
}
