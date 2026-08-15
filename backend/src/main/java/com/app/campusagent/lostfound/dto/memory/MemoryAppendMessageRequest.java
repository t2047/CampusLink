package com.app.campusagent.lostfound.dto.memory;

import com.app.campusagent.lostfound.domain.LfMessageRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/** 追加一条会话消息请求。 */
public record MemoryAppendMessageRequest(
        @NotNull LfMessageRole role,
        @NotBlank String messageText,
        @Size(max = 50) String intent,
        Map<String, Object> extractedFields,
        List<String> imageObjectKeys,
        @Size(max = 64) String traceId) {
}
