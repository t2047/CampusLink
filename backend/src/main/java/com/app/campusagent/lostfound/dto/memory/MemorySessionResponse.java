package com.app.campusagent.lostfound.dto.memory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 会话响应：滚动摘要 + pending 确认草稿 + 历史消息。 */
public record MemorySessionResponse(
        Long id,
        String sessionId,
        String title,
        String summary,
        Map<String, Object> pendingConfirmation,
        boolean archived,
        LocalDateTime lastActiveAt,
        List<MemoryMessageResponse> messages) {
}
