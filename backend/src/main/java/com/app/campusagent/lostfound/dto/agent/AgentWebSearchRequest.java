/**
 * Web【Browse 以图搜物】请求 DTO（dto/agent 子包）。
 *
 * <p>对应 {@code POST /api/lost-found/agent/search} 的请求体，
 * 登录用户在 Browse 页提交图片与可选筛选条件进行以图搜物，
 * 由 LostFoundAgentGateway 代理给 Agent 的轻量搜索端点。</p>
 */
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
        // 检索的报告类型：必填且必须是 FOUND（拾获）或 LOST（失物）之一
        @NotBlank(message = "reportType is required")
        @Pattern(regexp = "FOUND|LOST", message = "reportType must be FOUND or LOST")
        String reportType,
        // 关键词筛选：可选，最长 100 字符
        @Size(max = 100, message = "keyword is too long")
        String keyword,
        // 分类筛选：可选，最长 50 字符
        @Size(max = 50, message = "category is too long")
        String category,
        // 颜色筛选：可选，最长 50 字符
        @Size(max = 50, message = "colour is too long")
        String colour,
        // 地点筛选：可选，最长 200 字符
        @Size(max = 200, message = "location is too long")
        String location,
        // 事件日期范围起点（含）：可选
        LocalDate dateFrom,
        // 事件日期范围终点（含）：可选
        LocalDate dateTo,
        // 查询图片：必填且 1~5 张（以图搜物的核心输入）
        @NotNull(message = "images is required")
        @Valid
        @Size(min = 1, max = 5, message = "images must contain 1 to 5 images")
        List<AgentWebInvokeRequest.AgentImage> images) {

    /** 转成 Agent SearchRequest 的 snake_case 载荷；空筛选字段省略（避免注入幽灵文本分量）。 */
    public Map<String, Object> toAgentPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("report_type", reportType);
        // 仅写入非空筛选字段：空值省略，避免在 Agent 端形成多余文本分量干扰打分
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

    /** 判断字符串是否非空非空白（去除首尾空白后仍有内容）。 */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
