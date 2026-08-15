package com.app.campusagent.lostfound.dto.memory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** 建/更新会话请求（含滚动摘要与 pending 确认草稿）。

 * <p>pending 语义：pendingConfirmation 非 null 时写入；clearPendingConfirmation 为 true 时清除；
 * 两者皆缺省时不触碰已有 pending（upsert 对 pending 保持非破坏性，agent 每轮持久化摘要时
 * 不会误删上一轮未过期的确认草稿，见 chat-memory-requirements §7.5）。</p>
 */
public record MemoryUpsertSessionRequest(
        @NotBlank @Size(max = 200) String sessionId,
        @Size(max = 120) String title,
        String summary,
        Map<String, Object> pendingConfirmation,
        Boolean clearPendingConfirmation) {
}
