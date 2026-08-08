package com.app.campusagent.chat.controller;

import com.app.campusagent.chat.config.ChatProperties;
import com.app.campusagent.chat.service.DelegationTokenProvider;
import com.app.campusagent.dto.TokenExchangeRequest;
import com.app.campusagent.dto.TokenExchangeResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Delegation Token 兑换端点（Token Service 内嵌实现）。
 *
 * <p>接口形态与 Sprint 3 独立 Token Service 对齐：</p>
 * <ul>
 *   <li>{@code POST /internal/token/exchange} — 兑换 RS256 Delegation Token</li>
 *   <li>{@code GET /.well-known/jwks.json} — 公开 JWKS，供 Agent 端验签</li>
 * </ul>
 *
 * <p>安全：仅接受编排层的调用（共享密钥 HMAC 签名，与编排层入站中间件同一算法）；
 * 附带 Nonce + Timestamp 防重放。不经过用户 JWT 认证（编排层无用户 JWT，
 * 用户 JWT 不离开 Chat Backend）。</p>
 *
 * <p>HMAC 格式与 Python 侧对齐：{@code hex(hmac_sha256(body + ":" + nonce + ":" + timestamp))}
 * 小写 hex（{@code hmac.new(...).hexdigest()}）。</p>
 */
@RestController
public class TokenExchangeController {

    private static final Logger log = LoggerFactory.getLogger(TokenExchangeController.class);

    /** 时间窗口（秒），与编排层 {@code time_window_seconds} 对齐。 */
    private static final long TIME_WINDOW_SECONDS = 30;
    /** Nonce 防重放缓存 TTL（秒），与 Agent 端 {@code nonce_ttl_seconds} 对齐。 */
    private static final long NONCE_TTL_SECONDS = 60;
    /** jti 长度上限（防超长值放大 token 体积）。 */
    private static final int JTI_MAX_LENGTH = 128;
    /** 签名算法。 */
    private static final String ALGORITHM = "RS256";

    private final DelegationTokenProvider delegationTokenProvider;
    private final ChatProperties properties;
    private final ObjectMapper objectMapper;

    /** nonce → 首次使用时间，防重放（单实例内存去重；多实例生产可换 Redis SETNX）。 */
    private final Map<String, Long> nonceCache = new ConcurrentHashMap<>();

    public TokenExchangeController(DelegationTokenProvider delegationTokenProvider,
                                   ChatProperties properties,
                                   ObjectMapper objectMapper) {
        this.delegationTokenProvider = delegationTokenProvider;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 兑换 Delegation Token（仅编排层可调用）。
     *
     * <p>验证链：Header 完整性 → Timestamp 窗口 → HMAC 签名 → Nonce 一次性 →
     * 请求体必填字段校验。</p>
     */
    @PostMapping(value = "/internal/token/exchange", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TokenExchangeResponse exchange(@RequestBody String rawBody, WebRequest webRequest) {
        verifyInboundSignature(rawBody, webRequest);

        TokenExchangeRequest request = parseRequest(rawBody);

        String targetAgent = request.targetAgent();
        String jti = request.jti();
        if (jti != null && jti.length() > JTI_MAX_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "jti too long");
        }
        String intendedAction = (request.intendedAction() == null || request.intendedAction().isBlank())
                ? "invoke" : request.intendedAction();

        String token = delegationTokenProvider.issueDelegationToken(
                request.userId(), request.role(), targetAgent, intendedAction, request.jti());

        log.info("Delegation token issued: targetAgent={}, action={}, expiresIn={}s",
                targetAgent, intendedAction, properties.getDelegationTokenTtlSeconds());

        return new TokenExchangeResponse(
                token,
                properties.getDelegationTokenTtlSeconds(),
                ALGORITHM,
                delegationTokenProvider.getKeyId());
    }

    /**
     * 公开 JWKS 端点（Agent 端 RS256 验签用，仅含公钥）。
     */
    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> jwks() {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(delegationTokenProvider.jwkSetJson());
    }

    // ──────────────────────────────────────────────────────────────────────
    // 入站 HMAC 校验（编排层 → Chat Backend 方向）
    // ──────────────────────────────────────────────────────────────────────

    private void verifyInboundSignature(String body, WebRequest request) {
        // fail-fast：未配置共享密钥时拒绝所有兑换（防止空密钥伪造签名）
        String sharedSecret = properties.getSharedSecret();
        if (sharedSecret == null || sharedSecret.isBlank()) {
            log.error("app.chat.shared-secret 未配置 — 拒绝 Delegation Token 兑换");
            throw unauthorized("server not configured for token exchange");
        }

        String signature = request.getHeader("X-Signature");
        String nonce = request.getHeader("X-Nonce");
        String timestampStr = request.getHeader("X-Timestamp");

        if (signature == null || nonce == null || timestampStr == null) {
            throw unauthorized("missing security headers");
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(timestampStr);
        } catch (NumberFormatException e) {
            throw unauthorized("invalid timestamp");
        }

        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - timestamp) > TIME_WINDOW_SECONDS) {
            throw unauthorized("request expired — possible replay");
        }

        // 签名比对（constant-time）在 nonce 记录之前：无效签名请求不得污染 nonce 缓存
        String expected = sign(body, nonce, timestamp);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            throw unauthorized("signature mismatch");
        }

        // 签名通过后做 Nonce 一次性检查（60s 内不可复用）
        long usedAt = nonceCache.getOrDefault(nonce, -1L);
        if (usedAt != -1L && (now - usedAt) < NONCE_TTL_SECONDS) {
            throw unauthorized("nonce reused — replay detected");
        }
        nonceCache.put(nonce, now);
        // 惰性清理过期 nonce，防止缓存无限增长
        nonceCache.entrySet().removeIf(e -> (now - e.getValue()) >= NONCE_TTL_SECONDS);
    }

    private TokenExchangeRequest parseRequest(String rawBody) {
        try {
            JsonNode node = objectMapper.readTree(rawBody);
            String userId = node.path("userId").asText(null);
            String role = node.path("role").asText(null);
            String targetAgent = node.path("targetAgent").asText(null);
            String intendedAction = node.has("intendedAction") ? node.path("intendedAction").asText(null) : null;
            String jti = node.has("jti") ? node.path("jti").asText(null) : null;

            if (userId == null || userId.isBlank()
                    || role == null || role.isBlank()
                    || targetAgent == null || targetAgent.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "userId/role/targetAgent are required");
            }
            return new TokenExchangeRequest(userId, role, targetAgent, intendedAction, jti);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid request body", e);
        }
    }

    private RuntimeException unauthorized(String detail) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, detail);
    }

    private String sign(String body, String nonce, long timestamp) {
        String message = body + ":" + nonce + ":" + timestamp;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    properties.getSharedSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC signing failed", e);
        }
    }
}
