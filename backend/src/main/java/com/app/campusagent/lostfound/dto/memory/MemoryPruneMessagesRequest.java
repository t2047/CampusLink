package com.app.campusagent.lostfound.dto.memory;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** 摘要滚动后裁剪原文请求：仅保留最近 keepLatest 条。 */
public record MemoryPruneMessagesRequest(
        @Min(1) @Max(200) int keepLatest) {
}
