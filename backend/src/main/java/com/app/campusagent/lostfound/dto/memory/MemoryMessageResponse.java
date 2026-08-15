package com.app.campusagent.lostfound.dto.memory;

import com.app.campusagent.lostfound.domain.LfMessageRole;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 会话消息响应（原文 + 结构化抽取）。 */
public record MemoryMessageResponse(
        Long id,
        LfMessageRole role,
        String messageText,
        String intent,
        Map<String, Object> extractedFields,
        List<String> imageObjectKeys,
        String traceId,
        LocalDateTime createdAt) {
}
