/**
 * Web【物品分类建议】请求 DTO（dto/agent 子包）。
 *
 * <p>对应 {@code POST /api/lost-found/agent/classify} 的请求体，
 * 登录用户在手动上报页面提交物品名称，请求 Agent 返回分类建议。</p>
 */
package com.app.campusagent.lostfound.dto.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** 登录用户在手动上报页请求物品分类建议时提交的请求。 */
public record AgentClassifyWebRequest(
        // 物品名称：分类的依据，必填且最长 200 字符（超出会被拒绝）
        @NotBlank(message = "itemName is required")
        @Size(max = 200, message = "itemName must not exceed 200 characters")
        String itemName) {

    /**
     * 转成 Agent 端约定的 snake_case 载荷；
     * 发送前去除首尾空白，避免空白串被当作有效物品名。
     */
    public Map<String, Object> toAgentPayload() {
        return Map.of("item_name", itemName.trim());
    }
}
