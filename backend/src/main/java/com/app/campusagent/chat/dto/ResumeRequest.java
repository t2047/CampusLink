package com.app.campusagent.chat.dto;

/**
 * HITL 确认恢复请求（前端 → Chat Backend → 编排层 /chat/resume）。
 *
 * @param sessionId 会话 ID（必须与原始 /chat/stream 的 sessionId 一致，
 *                  编排层据此恢复挂起的 LangGraph checkpoint）
 * @param approved  用户确认结果（true=确认，false=取消）
 */
public record ResumeRequest(String sessionId, boolean approved) {
}
