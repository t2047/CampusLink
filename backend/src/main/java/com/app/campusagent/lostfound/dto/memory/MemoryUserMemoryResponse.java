package com.app.campusagent.lostfound.dto.memory;

import java.util.List;

/** 当前用户长期记忆事实集合。 */
public record MemoryUserMemoryResponse(List<MemoryFactResponse> facts) {
}
