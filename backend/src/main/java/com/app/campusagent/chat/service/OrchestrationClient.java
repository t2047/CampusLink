package com.app.campusagent.chat.service;

import com.app.campusagent.chat.config.ChatProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 编排层 HTTP 客户端 — Chat Core。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>把用户请求转发到编排层（Python LangGraph）{@code POST /chat/stream}</li>
 *   <li>构造安全 Headers（HMAC 签名 + Nonce + Timestamp）并透传</li>
 *   <li>将编排层 SSE 事件流<strong>增量解码</strong>为 {@code Flux<ServerSentEvent<String>>}，
 *       事件随到随发，不等待整个流结束（保证前端打字机效果）</li>
 *   <li>为每个目标 Agent 通过 {@link DelegationTokenProvider} 签发 RS256 Delegation Token</li>
 * </ul>
 *
 * <p>安全说明（对齐通信安全文档）：</p>
 * <ul>
 *   <li>原始用户 JWT 不离开 Chat Backend；Agent 只拿到 30s 有效的 Delegation Token</li>
 *   <li>Delegation Token 由本组件内嵌的 {@link DelegationTokenProvider} 以 RS256 签发</li>
 *   <li>Sprint 3+ 迁移：改为调用独立 Token Service {@code POST /internal/token/exchange}</li>
 * </ul>
 *
 * <p><b>SSE 增量解码说明：</b></p>
 * <ul>
 *   <li>改用 {@code bodyToFlux(DataBuffer.class)} 读取原始字节，绕过
 *       {@code ServerSentEventHttpMessageReader}（该 reader 在 Spring MVC + WebClient 组合下
 *       会剥离 event 名，导致前端收到全部 {@code message} 而无法渲染）。</li>
 *   <li><b>不攒批：</b>每次订阅创建独立的 {@link SseIncrementalParser}，按 {@code \n\n}
 *       事件边界边收边解析边发出；末尾 {@code flush()} 处理残留半包。</li>
 *   <li>event 名 100% 保留（{@code token} / {@code agent_start} / {@code agent_step} / …）。</li>
 * </ul>
 */
@Service
public class OrchestrationClient {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationClient.class);

    private final ChatProperties properties;
    private final WebClient webClient;
    private final DelegationTokenProvider delegationTokenProvider;
    private final ObjectMapper objectMapper;

    public OrchestrationClient(ChatProperties properties,
                               WebClient webClient,
                               DelegationTokenProvider delegationTokenProvider,
                               ObjectMapper objectMapper) {
        this.properties = properties;
        this.webClient = webClient;
        this.delegationTokenProvider = delegationTokenProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * 转发用户消息到编排层，返回<strong>增量解码</strong>后的 SSE 事件流。
     *
     * @param userJwt 原始用户 JWT（仅用于解析身份，不转发给 Agent）
     * @param userId  用户 ID（来自 SecurityContext）
     * @param role    用户角色
     * @param message 用户消息
     * @param traceId 分布式追踪 ID（不存在则生成）
     */
    public Flux<ServerSentEvent<String>> streamChat(String userJwt, String userId, String role,
                                                    String message, String traceId) {
        String resolvedTraceId = (traceId == null || traceId.isBlank())
                ? UUID.randomUUID().toString() : traceId;

        // 为编排层调用构建请求体（不含原始 JWT）
        String requestBody = buildRequestBody(userId, role, message, resolvedTraceId);

        // 构造安全 Headers（编排层共享密钥 HMAC 签名）
        HttpHeaders headers = buildSecureHeaders(requestBody, resolvedTraceId);

        log.info("Forwarding chat to orchestration: userId={}, traceId={}", userId, resolvedTraceId);

        // Flux.defer：每次订阅创建独立解析器，避免并发订阅共享可变状态
        return Flux.defer(() -> {
            SseIncrementalParser parser = new SseIncrementalParser();

            return webClient.post()
                    .uri(properties.getOrchestrationBaseUrl() + "/chat/stream")
                    .headers(h -> h.addAll(headers))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(requestBody)
                    .retrieve()
                    // ── 关键 1：原始字节读取，绕过 ServerSentEventHttpMessageReader ──
                    .bodyToFlux(DataBuffer.class)
                    .map(this::decodeDataBuffer)
                    // ── 关键 2：增量解析，事件随到随发（不攒批）──
                    .flatMapIterable(parser::feed)
                    // flush() 返回 List<ServerSentEvent<String>>，必须 flatMapMany 展开为
                    // 事件流，否则 Mono<List<T>> 与 Flux<T> 泛型不兼容（inference variable
                    // T has incompatible bounds）
                    .concatWith(Mono.fromCallable(parser::flush).flatMapMany(Flux::fromIterable))
                    .defaultIfEmpty(errorEvent("empty stream"))
                    .onErrorResume(e -> {
                        log.error("Orchestration call failed: traceId={}, err={}",
                                resolvedTraceId, e.getMessage());
                        return Flux.just(errorEvent("编排层暂时不可用"));
                    });
        });
    }

    /**
     * 为指定 Agent 签发 Delegation Token（RS256，30s TTL）。
     *
     * @param userId      用户 ID
     * @param role        用户角色
     * @param targetAgent 目标 Agent（aud）
     */
    public String exchangeForDelegationToken(String userId, String role, String targetAgent) {
        return delegationTokenProvider.issueDelegationToken(userId, role, targetAgent, "invoke");
    }

    // ──────────────────────────────────────────────────────────────────────
    // 增量 SSE 解析器
    // ──────────────────────────────────────────────────────────────────────

    /** 将单个 DataBuffer 解码为 UTF-8 文本并释放缓冲。 */
    private String decodeDataBuffer(DataBuffer buffer) {
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        DataBufferUtils.release(buffer);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 增量 SSE 解析器（每次订阅一个实例，线程隔离）。
     *
     * <p>{@code feed()} 接收一个分片，把其中完整的事件（以 {@code \n\n} 为边界）立即返回；
     * 半包残留在内部 buffer，等待下一个分片补齐。{@code flush()} 在流结束时处理残留。</p>
     */
    private static final class SseIncrementalParser {

        private final StringBuilder buffer = new StringBuilder();

        List<ServerSentEvent<String>> feed(String chunk) {
            // 统一 CRLF → LF，简化事件边界判断
            buffer.append(chunk.replace("\r\n", "\n"));

            List<ServerSentEvent<String>> events = new ArrayList<>();
            int boundary;
            while ((boundary = buffer.indexOf("\n\n")) != -1) {
                String block = buffer.substring(0, boundary);
                buffer.delete(0, boundary + 2);
                ServerSentEvent<String> evt = parseBlock(block);
                if (evt != null) {
                    events.add(evt);
                }
            }
            return events;
        }

        List<ServerSentEvent<String>> flush() {
            if (buffer.length() == 0) {
                return List.of();
            }
            String block = buffer.toString();
            buffer.setLength(0);
            ServerSentEvent<String> evt = parseBlock(block);
            return evt == null ? List.of() : List.of(evt);
        }

        private ServerSentEvent<String> parseBlock(String block) {
            if (block == null || block.isBlank()) {
                return null;
            }
            String event = "message";
            List<String> dataLines = new ArrayList<>();
            for (String line : block.split("\n")) {
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    // 按 SSE 规范去掉单个前导空格
                    dataLines.add(line.substring(5).replaceFirst("^ ", ""));
                }
            }
            if (dataLines.isEmpty()) {
                return null;
            }
            return ServerSentEvent.<String>builder()
                    .event(event)
                    .data(String.join("\n", dataLines))
                    .build();
        }
    }

    private ServerSentEvent<String> errorEvent(String message) {
        return ServerSentEvent.<String>builder()
                .event("error")
                .data("{\"message\":\"" + message + "\"}")
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────
    // 请求构建与签名
    // ──────────────────────────────────────────────────────────────────────

    private String buildRequestBody(String userId, String role, String message, String traceId) {
        try {
            JsonNode body = objectMapper.createObjectNode()
                    .put("userId", userId)
                    .put("role", role)
                    .put("message", message)
                    .put("traceId", traceId);
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build request body", e);
        }
    }

    private HttpHeaders buildSecureHeaders(String requestBody, String traceId) {
        String nonce = UUID.randomUUID().toString();
        long timestamp = Instant.now().getEpochSecond();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Nonce", nonce);
        headers.set("X-Timestamp", String.valueOf(timestamp));
        headers.set("X-Signature", sign(requestBody, nonce, timestamp));
        headers.set("X-Trace-Id", traceId);
        headers.set("X-Service", "chat-backend");
        return headers;
    }

    /**
     * HMAC-SHA256 请求签名（与编排层/Agent 安全中间件一致）。
     *
     * <p>格式约定：小写 hex（与 Python 侧 {@code hmac.new(...).hexdigest()} 对齐，
     * 禁止使用 Base64，否则签名校验必然 401）。</p>
     */
    private String sign(String body, String nonce, long timestamp) {
        String message = body + ":" + nonce + ":" + timestamp;
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    properties.getSharedSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest); // 小写 hex，与 Python hexdigest() 一致
        } catch (Exception e) {
            throw new IllegalStateException("HMAC signing failed", e);
        }
    }
}
