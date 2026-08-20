/**
 * 失物招领模块与 Agent 服务之间的安全通信网关。
 *
 * <p><b>职责</b>：Web/后端作为可信调用方，把前端请求安全地代理给失物招领 Agent
 * （lost-found-agent，默认地址 http://localhost:8083）。支持三类操作：
 * <ul>
 *     <li>{@link #invoke} —— 通用对话/意图执行（调用 /agent/invoke）；</li>
 *     <li>{@link #classify} —— 图片分类（调用 /agent/classify）；</li>
 *     <li>{@link #search} —— Browse 以图搜物（调用 /agent/search）。</li>
 * </ul>
 *
 * <p><b>被谁调用</b>：由 {@code LostFoundReportController} 等 Web 控制器在收到用户请求后调用，
 * 网关负责转发并校验响应结构，把 Agent 的异常转换为统一的 {@link LostFoundApiException}。
 *
 * <p><b>依赖</b>：注入 {@code LostFoundImageStagingService}（把上传暂存图替换为可信的
 * 视觉指纹/向量元数据后再下发给 Agent）；共享密钥 {@code app.agent.shared-secret}（仅存服务端，
 * 用于签发 JWT 委托令牌与 HMAC 请求签名，绝不下发到浏览器）。
 */
package com.app.campusagent.lostfound.service;

import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.dto.agent.AgentClassifyResponse;
import com.app.campusagent.lostfound.dto.agent.AgentClassifyWebRequest;
import com.app.campusagent.lostfound.dto.agent.AgentWebInvokeRequest;
import com.app.campusagent.lostfound.dto.agent.AgentWebSearchRequest;
import com.app.campusagent.lostfound.exception.LostFoundApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * Web 与 Agent 之间的安全代理。共享密钥只保存在服务端，绝不下发到浏览器。
 *
 * <p>本类为 Spring 单例服务，持有线程安全的 {@link HttpClient} 与 {@link ObjectMapper}；
 * 每次调用都会为当前用户生成一次性委托令牌与请求签名，实现服务端到服务端的双向信任。</p>
 */
@Service
public class LostFoundAgentGateway {

    /** Agent 服务的固定名称，作为 JWT 令牌的 audience（受众），防止令牌被转发到其他服务。 */
    private static final String AGENT_NAME = "lost-found-agent";

    /** 请求 Agent 的整体超时：20 秒。超过即按"Agent 暂时不可用"处理。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI invokeUri;
    private final URI classifyUri;
    private final URI searchUri;
    private final String sharedSecret;
    private final LostFoundImageStagingService stagingService;

    /** Spring 注入用主构造器：读取配置并用 5 秒连接超时的 HTTP/1.1 客户端。 */
    @Autowired
    public LostFoundAgentGateway(
            @Value("${app.agent.lost-found-url:http://localhost:8083}") String agentUrl,
            @Value("${app.agent.shared-secret:}") String sharedSecret,
            LostFoundImageStagingService stagingService) {
        this(
                new ObjectMapper(),
                // 使用 HTTP/1.1 并设置 5 秒连接超时，避免内网 Agent 挂起时阻塞过长
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(5))
                        .build(),
                // 去尾部斜杠后拼接 /agent/invoke 作为通用调用端点
                URI.create(agentUrl.replaceAll("/+$", "") + "/agent/invoke"),
                sharedSecret,
                stagingService);
    }

    /** 测试用便捷构造器（无暂存服务，一般用于单元测试）。 */
    LostFoundAgentGateway(
            ObjectMapper objectMapper,
            HttpClient httpClient,
            URI invokeUri,
            String sharedSecret) {
        this(objectMapper, httpClient, invokeUri, sharedSecret, null);
    }

    /** 测试用构造器：由 invoke 端点推导 classify 端点。 */
    LostFoundAgentGateway(
            ObjectMapper objectMapper,
            HttpClient httpClient,
            URI invokeUri,
            String sharedSecret,
            LostFoundImageStagingService stagingService) {
        this(
                objectMapper,
                httpClient,
                invokeUri,
                // 由 /agent/invoke 推导出 /agent/classify 端点
                URI.create(invokeUri.toString().replace("/agent/invoke", "/agent/classify")),
                sharedSecret,
                stagingService);
    }

    /** 最终私有构造器：初始化全部字段，并推导出 search 端点。 */
    private LostFoundAgentGateway(
            ObjectMapper objectMapper,
            HttpClient httpClient,
            URI invokeUri,
            URI classifyUri,
            String sharedSecret,
            LostFoundImageStagingService stagingService) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.invokeUri = invokeUri;
        this.classifyUri = classifyUri;
        // 由 /agent/invoke 推导出 /agent/search 端点
        this.searchUri = URI.create(invokeUri.toString().replace("/agent/invoke", "/agent/search"));
        this.sharedSecret = sharedSecret;
        this.stagingService = stagingService;
    }

    /**
     * 通用对话/意图执行入口：把用户的 Agent 请求转发到 /agent/invoke。
     *
     * @param request     前端提交的 Agent 调用请求（含消息与可选图片）
     * @param currentUser 当前登录用户（用于签发委托令牌与替换可信图片）
     * @return Agent 返回的键值结构；必须含 String 类型的 "response" 与 "status" 字段
     * @throws LostFoundApiException 未配置 / Agent 不可用 / 响应结构非法时抛出
     */
    public Map<String, Object> invoke(AgentWebInvokeRequest request, User currentUser) {
        ensureConfigured();
        // 每个请求生成独立 traceId，便于跨服务链路追踪
        String traceId = UUID.randomUUID().toString();
        byte[] body;
        try {
            // 组装发给 Agent 的载荷（写入 traceId）
            Map<String, Object> agentPayload = request.toAgentPayload(traceId);
            // 把用户上传的图片替换为暂存服务计算出的可信元数据（指纹/向量），
            // 避免直接把不可信 URL 透传给 Agent
            replaceWithTrustedImages(agentPayload, request.images(), currentUser);
            body = objectMapper.writeValueAsBytes(agentPayload);
        } catch (JsonProcessingException exception) {
            // 序列化失败说明入参结构非法，按"Agent 返回了非法响应"处理
            throw new LostFoundApiException(
                    HttpStatus.BAD_GATEWAY,
                    "AGENT_INVALID_RESPONSE",
                    "Lost & Found Agent returned an invalid response",
                    exception);
        }
        Map<String, Object> payload = callAgent(invokeUri, body, "invoke", currentUser, traceId);
        // 校验响应必须同时包含字符串类型的 "response" 与 "status"，避免下游 NPE
        if (!(payload.get("response") instanceof String)
                || !(payload.get("status") instanceof String)) {
            throw new LostFoundApiException(
                    HttpStatus.BAD_GATEWAY,
                    "AGENT_INVALID_RESPONSE",
                    "Lost & Found Agent returned an invalid response");
        }
        return payload;
    }

    /**
     * 图片分类入口：把分类请求转发到 /agent/classify。
     *
     * @param request     前端提交的分类请求
     * @param currentUser 当前登录用户
     * @return 封装了 Agent 识别出的图片分类结果；若缺 "category" 字段则 category 为 null
     */
    public AgentClassifyResponse classify(AgentClassifyWebRequest request, User currentUser) {
        ensureConfigured();
        String traceId = UUID.randomUUID().toString();
        byte[] body;
        try {
            // classify 无图片，直接序列化载荷
            body = objectMapper.writeValueAsBytes(request.toAgentPayload());
        } catch (JsonProcessingException exception) {
            throw new LostFoundApiException(
                    HttpStatus.BAD_GATEWAY,
                    "AGENT_INVALID_RESPONSE",
                    "Lost & Found Agent returned an invalid response",
                    exception);
        }
        Map<String, Object> payload = callAgent(classifyUri, body, "classify", currentUser, traceId);
        Object category = payload.get("category");
        // 仅当 category 为字符串时才透传，否则置 null（避免类型不安全）
        return new AgentClassifyResponse(category instanceof String ? (String) category : null);
    }

    /**
     * 用暂存服务换算出的可信图片元数据替换载荷里的原始图片列表。
     * 仅当暂存服务可用且存在图片时才执行替换。
     */
    private void replaceWithTrustedImages(
            Map<String, Object> payload,
            java.util.List<AgentWebInvokeRequest.AgentImage> images,
            User currentUser) {
        if (stagingService != null && images != null && !images.isEmpty()) {
            // trustedAgentImages 会校验图片归属当前用户且未过期，并附上视觉指纹/向量
            payload.put("images", stagingService.trustedAgentImages(images, currentUser));
        }
    }

    /**
     * Browse 以图搜物：把查询（含视觉指纹）安全代理给 Agent 的轻量搜索端点。
     * 响应只要求含 String 类型的 "status" 字段即可。
     */
    public Map<String, Object> search(AgentWebSearchRequest request, User currentUser) {
        ensureConfigured();
        String traceId = UUID.randomUUID().toString();
        byte[] body;
        try {
            Map<String, Object> agentPayload = request.toAgentPayload();
            replaceWithTrustedImages(agentPayload, request.images(), currentUser);
            body = objectMapper.writeValueAsBytes(agentPayload);
        } catch (JsonProcessingException exception) {
            throw new LostFoundApiException(
                    HttpStatus.BAD_GATEWAY,
                    "AGENT_INVALID_RESPONSE",
                    "Lost & Found Agent returned an invalid response",
                    exception);
        }
        Map<String, Object> payload = callAgent(searchUri, body, "search", currentUser, traceId);
        if (!(payload.get("status") instanceof String)) {
            throw new LostFoundApiException(
                    HttpStatus.BAD_GATEWAY,
                    "AGENT_INVALID_RESPONSE",
                    "Lost & Found Agent returned an invalid response");
        }
        return payload;
    }

    /**
     * 统一发起到 Agent 的 HTTP POST 请求，附带 JWT 委托令牌与 HMAC 请求签名。
     *
     * <p>安全设计：令牌绑定用户身份、目标 action、30 秒有效期与一次性 nonce；
     * 请求体用共享密钥做 HmacSHA256 签名，保证内容与时间戳未被篡改。</p>
     *
     * @param uri          目标端点
     * @param body         序列化后的请求体字节
     * @param intendedAction 声明意图（invoke/classify/search），写入令牌与请求中
     * @param currentUser  当前用户（令牌 subject 与 role 的来源）
     * @param traceId      链路追踪 id，透传给 Agent
     * @return 反序列化后的 Agent 响应 Map
     */
    private Map<String, Object> callAgent(
            URI uri,
            byte[] body,
            String intendedAction,
            User currentUser,
            String traceId) {
        // nonce 防重放：每次请求唯一
        String nonce = UUID.randomUUID().toString();
        // 时间戳用于签名校验与令牌有效期，秒级精度
        long timestamp = Instant.now().getEpochSecond();
        try {
            // 组装请求：Authorization 承载委托 JWT，另以 X-Nonce / X-Timestamp / X-Signature
            // 三个请求头实现请求体签名，X-Trace-Id 用于链路追踪
            HttpRequest agentRequest = HttpRequest.newBuilder(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + delegationToken(
                            currentUser, nonce, timestamp, intendedAction))
                    .header("Content-Type", "application/json")
                    .header("X-Nonce", nonce)
                    .header("X-Timestamp", Long.toString(timestamp))
                    .header("X-Signature", signature(body, nonce, timestamp))
                    .header("X-Trace-Id", traceId)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            // 阻塞发送请求，等待 Agent 返回字节流
            HttpResponse<byte[]> response = httpClient.send(
                    agentRequest,
                    HttpResponse.BodyHandlers.ofByteArray());
            // 非 2xx 一律视为 Agent 拒绝请求
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new LostFoundApiException(
                        HttpStatus.BAD_GATEWAY,
                        "AGENT_REQUEST_FAILED",
                        "Lost & Found Agent rejected the request");
            }
            // 将响应体反序列化为 Map（泛型由 TypeReference 推导）
            return objectMapper.readValue(
                    response.body(),
                    new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            // 响应体不是合法 JSON
            throw new LostFoundApiException(
                    HttpStatus.BAD_GATEWAY,
                    "AGENT_INVALID_RESPONSE",
                    "Lost & Found Agent returned an invalid response",
                    exception);
        } catch (IOException exception) {
            // 网络层 I/O 失败（连接断开、超时等）→ Agent 不可用
            throw unavailable(exception);
        } catch (InterruptedException exception) {
            // 被中断时恢复中断标志位后继续抛"不可用"
            Thread.currentThread().interrupt();
            throw unavailable(exception);
        } catch (GeneralSecurityException exception) {
            // 密钥或 HMAC 配置非法
            throw new LostFoundApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "AGENT_SECURITY_CONFIGURATION_INVALID",
                    "Lost & Found Agent security is not configured correctly",
                    exception);
        }
    }

    /**
     * 启动前校验：共享密钥必须已配置且至少 32 字节（HS256 要求 ≥32 字节密钥），
     * 否则 Agent 通信无法安全进行，直接按未配置处理。
     */
    private void ensureConfigured() {
        if (sharedSecret == null || sharedSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new LostFoundApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "AGENT_NOT_CONFIGURED",
                    "Lost & Found Agent is not configured");
        }
    }

    /**
     * 生成供 Agent 校验的 JWT 委托令牌（HS256）。
     *
     * <p>令牌内容：subject=用户 id、claim role=用户角色、audience=lost-found-agent、
     * issuer=chat-core、30 秒有效期、jti=nonce、claim intended_action=声明意图。
     * Agent 侧会校验签名与以上字段，从而确认请求来自可信的 chat-core 且代表该用户。</p>
     */
    private String delegationToken(
            User currentUser, String nonce, long timestamp, String intendedAction) {
        // 用共享密钥构造 HMAC 签名密钥
        SecretKey key = Keys.hmacShaKeyFor(sharedSecret.getBytes(StandardCharsets.UTF_8));
        Date issuedAt = Date.from(Instant.ofEpochSecond(timestamp));
        return Jwts.builder()
                .subject(currentUser.getId().toString())
                .claim("role", currentUser.getRole().name())
                .audience().add(AGENT_NAME).and()
                .issuer("chat-core")
                .issuedAt(issuedAt)
                // 令牌 30 秒内有效，缩小被重放利用的窗口
                .expiration(Date.from(issuedAt.toInstant().plusSeconds(30)))
                .id(nonce)
                .claim("intended_action", intendedAction)
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 计算请求体的 HMAC-SHA256 签名（十六进制小写）。
     *
     * <p>签名输入为 {@code body + ':' + nonce + ':' + timestamp}，用共享密钥计算，
     * 使 Agent 能校验请求体、防重放 nonce 与时间戳都未被篡改。</p>
     *
     * @throws GeneralSecurityException 平台不支持 HmacSHA256 时抛出
     */
    private String signature(byte[] body, String nonce, long timestamp)
            throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update(body);
        mac.update((byte) ':');
        mac.update(nonce.getBytes(StandardCharsets.UTF_8));
        mac.update((byte) ':');
        // 将时间戳字节追加进 HMAC 输入后一次性计算摘要（与 body/nonce 一起参与签名）
        byte[] digest = mac.doFinal(Long.toString(timestamp).getBytes(StandardCharsets.UTF_8));
        // 用 HexFormat 把字节数组格式化为小写十六进制字符串
        return java.util.HexFormat.of().formatHex(digest);
    }

    /** 统一构造"Agent 暂时不可用"异常（503），包装底层异常便于排查。 */
    private LostFoundApiException unavailable(Exception exception) {
        return new LostFoundApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AGENT_UNAVAILABLE",
                "Lost & Found Agent is temporarily unavailable",
                exception);
    }
}
