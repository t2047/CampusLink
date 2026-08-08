package com.app.campusagent.dto;

/**
 * Delegation Token 兑换请求（编排层 → Token Service / Chat Backend 内嵌实现）。
 *
 * <p>接口形态与独立 Token Service 的 {@code POST /internal/token/exchange} 对齐：
 * Sprint 3+ 独立部署时仅需切换 {@code TOKEN_SERVICE_URL}，请求体保持不变。
 * 必填字段校验由 {@code TokenExchangeController} 手动执行。</p>
 *
 * @param userId         用户 ID（来自 Chat Backend 转发给编排层的可信身份）
 * @param role           用户角色（如 STUDENT）
 * @param targetAgent    目标 Agent 名称（aud，防跨 Agent 滥用）
 * @param intendedAction 预期操作（默认 invoke）
 * @param jti            请求唯一 ID：编排层将用它作为随后调用 Agent 时的
 *                       {@code X-Nonce}，使 Agent 端可校验 {@code claims.jti == X-Nonce}
 */
public record TokenExchangeRequest(
        String userId,
        String role,
        String targetAgent,
        String intendedAction,
        String jti
) {
}
