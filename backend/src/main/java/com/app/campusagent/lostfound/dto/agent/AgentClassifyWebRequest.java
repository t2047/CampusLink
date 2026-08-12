package com.app.campusagent.lostfound.dto.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** 登录用户在手动上报页请求物品分类建议时提交的请求。 */
public record AgentClassifyWebRequest(
        @NotBlank(message = "itemName is required")
        @Size(max = 200, message = "itemName must not exceed 200 characters")
        String itemName) {

    public Map<String, Object> toAgentPayload() {
        return Map.of("item_name", itemName.trim());
    }
}
