package com.app.campusagent.lostfound.dto.agent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Browse 以图搜物提交给 Agent 的轻量搜索请求（不经聊天/LLM，直接走候选检索与打分）。 */
public record AgentWebSearchRequest(
        @NotBlank(message = "reportType is required")
        @Pattern(regexp = "FOUND|LOST", message = "reportType must be FOUND or LOST")
        String reportType,
        @Size(max = 100, message = "keyword is too long")
        String keyword,
        @Size(max = 50, message = "category is too long")
        String category,
        @Size(max = 50, message = "colour is too long")
        String colour,
        @Size(max = 200, message = "location is too long")
        String location,
        LocalDate dateFrom,
        LocalDate dateTo,
        @NotNull(message = "images is required")
        @Valid
        @Size(min = 1, max = 5, message = "images must contain 1 to 5 images")
        List<AgentWebInvokeRequest.AgentImage> images) {

    /** 转成 Agent SearchRequest 的 snake_case 载荷；空筛选字段省略（避免注入幽灵文本分量）。 */
    public Map<String, Object> toAgentPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("report_type", reportType);
        if (hasText(keyword)) payload.put("keyword", keyword.trim());
        if (hasText(category)) payload.put("category", category.trim());
        if (hasText(colour)) payload.put("colour", colour.trim());
        if (hasText(location)) payload.put("location", location.trim());
        if (dateFrom != null) payload.put("date_from", dateFrom.toString());
        if (dateTo != null) payload.put("date_to", dateTo.toString());
        payload.put("images", images.stream()
                .map(AgentWebInvokeRequest::agentImagePayload)
                .toList());
        return payload;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
