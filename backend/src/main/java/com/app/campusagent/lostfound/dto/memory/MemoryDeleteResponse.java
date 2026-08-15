package com.app.campusagent.lostfound.dto.memory;

/** 清除用户记忆结果：删除的事实与会话计数。 */
public record MemoryDeleteResponse(long deletedFacts, long deletedSessions) {
}
