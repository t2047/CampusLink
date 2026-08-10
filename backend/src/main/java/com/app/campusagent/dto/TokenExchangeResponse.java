package com.app.campusagent.dto;

/**
 * Delegation Token 兑换响应（Token Service 接口形态，与独立服务保持一致）。
 *
 * @param token            RS256 签名的 Delegation Token（JWT）
 * @param expiresInSeconds Token 有效期（秒，默认 30）
 * @param algorithm        签名算法（RS256）
 * @param kid              签名密钥指纹（RFC 7638，Agent 端 JWKS 匹配用）
 */
public record TokenExchangeResponse(
        String token,
        long expiresInSeconds,
        String algorithm,
        String kid
) {
}
