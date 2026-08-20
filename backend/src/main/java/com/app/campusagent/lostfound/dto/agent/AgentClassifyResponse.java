/**
 * Agent【分类建议】响应 DTO（dto/agent 子包）。
 *
 * <p>对应 {@code POST /api/lost-found/agent/classify} 的响应体，
 * 返回对物品名称的自动分类建议。</p>
 */
package com.app.campusagent.lostfound.dto.agent;

/** Agent 分类建议响应；category 为 null 表示规则与 LLM 均无法判断。 */
public record AgentClassifyResponse(String category) {
    // 建议的物品分类（ItemCategory 名称）；null 表示规则与 LLM 均无法判断，前端可提示用户手动选择
}
