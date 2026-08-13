package com.app.campusagent.lostfound.dto.agent;

/** Agent 分类建议响应；category 为 null 表示规则与 LLM 均无法判断。 */
public record AgentClassifyResponse(String category) {
}
