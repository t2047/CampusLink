package com.app.campusagent.lostfound.dto.memory;

/** 消息裁剪结果：保留数与删除数。 */
public record MemoryPruneResponse(long kept, long deleted) {
}
