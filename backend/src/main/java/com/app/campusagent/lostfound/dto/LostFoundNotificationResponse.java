/**
 * 失物招领通知响应 DTO（响应体）。
 * <p>
 * 用户在通知中心看到的某条站内通知（认领申请提交、审核结果、认领成功等事件触发的消息），
 * 使用 Java record 表示。
 */
package com.app.campusagent.lostfound.dto;

import com.app.campusagent.lostfound.domain.NotificationType;

import java.time.Instant;

public record LostFoundNotificationResponse(
        Long id,                  // 通知 ID
        NotificationType type,    // 通知类型（如 CLAIM_SUBMITTED / CLAIM_DECIDED 等）
        Long reportId,            // 关联的招领单 ID（可能为 null）
        Long claimId,             // 关联的认领申请 ID（可能为 null）
        String title,             // 通知标题
        String message,           // 通知正文
        boolean read,             // 是否已读，前端据此显示未读角标/样式
        Instant createdAt,        // 通知创建时间
        Instant readAt            // 已读时间（未读时为 null）
) {
}
