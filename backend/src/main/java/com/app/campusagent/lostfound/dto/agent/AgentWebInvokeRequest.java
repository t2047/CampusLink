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
        @NotBlank(message = "message is required")
        @Size(max = 4000, message = "message must not exceed 4000 characters")
        String message,
        @Valid AgentConversationContext conversationContext,
        Boolean confirmed,
        @Size(max = 200, message = "confirmationId is too long")
        String confirmationId,
        @Valid @Size(max = 5, message = "images must not exceed 5")
        List<AgentImage> images) {

    public record AgentConversationContext(
            @Size(max = 200, message = "sessionId is too long") String sessionId,
            @Size(max = 20, message = "sharedData contains too many fields")
            Map<String, Object> sharedData) {
    }

    /** Agent 面板选中并已暂存的一张图片：objectKey 用于确认创建时关联落库。 */
    public record AgentImage(
            @NotBlank(message = "objectKey is required")
            @Size(max = 500, message = "objectKey is too long")
            String objectKey,
            @Size(max = 512, message = "visualFingerprint is too long")
            String visualFingerprint,
            @Size(max = 500, message = "url is too long")
            String url) {
    }

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

    static Map<String, Object> agentImagePayload(AgentImage image) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("object_key", image.objectKey());
        item.put("visual_fingerprint", image.visualFingerprint());
        item.put("url", image.url());
        return item;
    }
}
