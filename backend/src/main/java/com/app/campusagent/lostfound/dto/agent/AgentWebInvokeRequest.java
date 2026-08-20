/**
 * Web【Agent 测试对话】请求 DTO（dto/agent 子包）。
 *
 * <p>对应 {@code POST /api/lost-found/agent/invoke} 的请求体，
 * 登录用户在 Agent 面板与 Lost & Found Agent 对话时提交的消息、
 * 会话上下文、确认信息以及已暂存的图片，由 LostFoundAgentGateway 代理转发。</p>
 */
package com.app.campusagent.lostfound.dto.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 登录用户通过 Web 测试 Lost & Found Agent 时提交的请求。 */
public record AgentWebInvokeRequest(
        // 用户输入的消息文本：必填，最长 4000 字符
        @NotBlank(message = "message is required")
        @Size(max = 4000, message = "message must not exceed 4000 characters")
        String message,
        // 会话上下文（嵌套 AgentConversationContext）：sessionId 与会话共享数据，可为 null
        @Valid AgentConversationContext conversationContext,
        // 是否已确认：Agent 需要用户确认后才执行写操作（如创建报告），
        // 为 null/false 时 Agent 仅返回确认提示
        Boolean confirmed,
        // 确认 id：配合 confirmed 使用，标识用户确认的是哪一次 Agent 提议
        @Size(max = 200, message = "confirmationId is too long")
        String confirmationId,
        // 用户暂存的图片列表（嵌套 AgentImage），最多 5 张
        @Valid @Size(max = 5, message = "images must not exceed 5")
        List<AgentImage> images) {

    /**
     * 会话上下文：携带前端透传的会话标识与共享数据，
     * 用于在 Agent 端保持多轮对话状态。
     */
    public record AgentConversationContext(
            // 会话标识（前端生成的对话 id），最长 200 字符
            @Size(max = 200, message = "sessionId is too long") String sessionId,
            // 会话共享数据（透传键值对），最多 20 个字段
            @Size(max = 20, message = "sharedData contains too many fields")
            Map<String, Object> sharedData) {
    }

    /** Agent 面板选中并已暂存的一张图片：objectKey 用于确认创建时关联落库。 */
    public record AgentImage(
            // 已暂存图片的 objectKey（必填）：确认创建报告时用于关联落库
            @NotBlank(message = "objectKey is required")
            @Size(max = 500, message = "objectKey is too long")
            String objectKey,
            // 图片视觉指纹（可选）：用于以图搜物/相似度匹配
            @Size(max = 512, message = "visualFingerprint is too long")
            String visualFingerprint,
            // 图片回显 URL（可选）：面板展示用
            @Size(max = 500, message = "url is too long")
            String url) {
    }

    /**
     * 转成 Agent 端约定的 snake_case 载荷。
     * <p>message 去除首尾空白；conversationContext 为 null 时回退为空上下文；
     * confirmed 非 true 一律视为 false；images 非空时才写入。</p>
     */
    public Map<String, Object> toAgentPayload(String traceId) {
        Map<String, Object> context = new LinkedHashMap<>();
        AgentConversationContext activeContext = conversationContext == null
                ? new AgentConversationContext(null, Map.of())
                : conversationContext;
        context.put("session_id", activeContext.sessionId());
        context.put("shared_data", activeContext.sharedData() == null
                ? Map.of()
                : activeContext.sharedData());

        Map<String, Object> traceParent = new LinkedHashMap<>();
        traceParent.put("trace_id", traceId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", message.trim());
        payload.put("conversation_context", context);
        payload.put("confirmed", Boolean.TRUE.equals(confirmed));
        payload.put("confirmation_id", confirmationId);
        payload.put("trace_parent", traceParent);
        if (images != null && !images.isEmpty()) {
            payload.put("images", images.stream()
                    .map(AgentWebInvokeRequest::agentImagePayload)
                    .toList());
        }
        return payload;
    }

    /** 单张图片转成 Agent 端约定的 snake_case 载荷。 */
    static Map<String, Object> agentImagePayload(AgentImage image) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("object_key", image.objectKey());
        item.put("visual_fingerprint", image.visualFingerprint());
        item.put("url", image.url());
        return item;
    }
}
