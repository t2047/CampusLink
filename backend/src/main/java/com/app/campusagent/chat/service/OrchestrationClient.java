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

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
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
 * </ul>
 *
 * <p>安全说明（对齐通信安全文档）：</p>
 * <ul>
 *   <li>原始用户 JWT 不离开 Chat Backend；Agent 只拿到 30s 有效的 Delegation Token</li>
 *   <li>Delegation Token 由内嵌 Token Service（{@link com.app.campusagent.chat.controller.TokenExchangeController}）
 *       以 RS256 签发，编排层按需兑换（{@code POST /internal/token/exchange}）</li>
 *   <li>Sprint 3+ 迁移：Token Service 独立部署，仅切换 {@code TOKEN_SERVICE_URL}，接口形态不变</li>
 * </ul>
 *
 * <p><b>SSE 增量解码说明：</b></p>
 * <ul>
 *   <li>改用 {@code bodyToFlux(DataBuffer.class)} 读取原始字节，绕过
 *       {@code ServerSentEventHttpMessageReader}（该 reader 在 Spring MVC + WebClient 组合下
 *       会剥离 event 名，导致前端收到全部 {@code message} 而无法渲染）。</li>
 *   <li><b>不攒批：</b>每次订阅创建独立的 {@link SseIncrementalParser}，按行状态机解析：
 *       {@code event:} 行视为新事件边界、空行触发事件提交，边收边解析边发出；
 *       末尾 {@code flush()} 处理残留半包。兼容上游缺空行分隔的情况。</li>
 *   <li>event 名 100% 保留（{@code token} / {@code agent_start} / {@code agent_step} / …）。</li>
 * </ul>
 */
@Service
public class OrchestrationClient {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationClient.class);

    private final ChatProperties properties;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public OrchestrationClient(ChatProperties properties,
                               WebClient webClient,
                               ObjectMapper objectMapper) {
        this.properties = properties;
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 转发用户消息到编排层，返回<strong>增量解码</strong>后的 SSE 事件流。
     *
     * @param userJwt 原始用户 JWT（仅用于解析身份，不转发给 Agent）
     * @param userId  用户 ID（来自 SecurityContext）
     * @param role    用户角色
     * @param message 用户消息
     * @param sessionId 会话 ID（多轮上下文，透传给编排层作为 thread_id）
     * @param traceId 分布式追踪 ID（不存在则生成）
     */
    public Flux<ServerSentEvent<String>> streamChat(String userJwt, String userId, String role,
                                                    String message, String sessionId,
                                                    String traceId) {
        String resolvedTraceId = (traceId == null || traceId.isBlank())
                ? UUID.randomUUID().toString() : traceId;

        // 为编排层调用构建请求体（不含原始 JWT）
        String requestBody = buildRequestBody(userId, role, message, sessionId, resolvedTraceId);

        // 构造安全 Headers（编排层共享密钥 HMAC 签名）
        HttpHeaders headers = buildSecureHeaders(requestBody, resolvedTraceId);

        log.info("Forwarding chat to orchestration: userId={}, traceId={}", userId, resolvedTraceId);

        // Flux.defer：每次订阅创建独立解析器与解码器，避免并发订阅共享可变状态
        return Flux.defer(() -> {
            SseIncrementalParser parser = new SseIncrementalParser();
            // 有状态 UTF-8 解码器：跨网络分片保留不完整的多字节序列。
            // （new String(bytes, UTF_8) 逐分片解码会把切断在分片边界的汉字
            //   替换成 U+FFFD（��），如"但这��是统计上的巧合"）
            CharsetDecoder utf8 = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE);

            return webClient.post()
                    .uri(properties.getOrchestrationBaseUrl() + "/chat/stream")
                    .headers(h -> h.addAll(headers))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(requestBody)
                    .retrieve()
                    // ── 关键 1：原始字节读取，绕过 ServerSentEventHttpMessageReader ──
                    .bodyToFlux(DataBuffer.class)
                    .map(buf -> decodeDataBuffer(buf, utf8))
                    // ── 关键 2：增量解析，事件随到随发（不攒批）──
                    .flatMapIterable(parser::feed)
                    // flush() 返回 List<ServerSentEvent<String>>，必须 flatMapMany 展开为
                    // 事件流，否则 Mono<List<T>> 与 Flux<T> 泛型不兼容（inference variable
                    // T has incompatible bounds）
                    .concatWith(Mono.fromCallable(() -> {
                        // 流结束：decoder 收尾（endOfInput=true）处理尾部残留字节，
                        // 再交给 parser 提交残留事件
                        String tail = decodeTail(utf8);
                        List<ServerSentEvent<String>> events = new ArrayList<>();
                        if (!tail.isEmpty()) {
                            events.addAll(parser.feed(tail));
                        }
                        events.addAll(parser.flush());
                        return events;
                    }).flatMapMany(Flux::fromIterable))
                    .defaultIfEmpty(errorEvent("empty stream"))
                    .onErrorResume(e -> {
                        log.error("Orchestration call failed: traceId={}, err={}",
                                resolvedTraceId, e.getMessage());
                        return Flux.just(errorEvent("编排层暂时不可用"));
                    });
        });
    }

    // ──────────────────────────────────────────────────────────────────────
    // 增量 SSE 解析器
    // ──────────────────────────────────────────────────────────────────────

    /** 用有状态解码器将单个 DataBuffer 解码为 UTF-8 文本并释放缓冲。 */
    private static String decodeDataBuffer(DataBuffer buffer, CharsetDecoder utf8) {
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        DataBufferUtils.release(buffer);
        CharBuffer out = CharBuffer.allocate(bytes.length + 64);
        // endOfInput=false：遇不完整多字节序列返回 UNDERFLOW 并保留在解码器状态中，
        // 等待下一个分片续接，不会产生 U+FFFD
        utf8.decode(ByteBuffer.wrap(bytes), out, false);
        out.flip();
        return out.toString();
    }

    /** 流结束时收尾解码器（endOfInput=true），处理尾部残留字节。 */
    private static String decodeTail(CharsetDecoder utf8) {
        CharBuffer out = CharBuffer.allocate(64);
        utf8.decode(ByteBuffer.allocate(0), out, true);
        out.flip();
        return out.toString();
    }

    /**
     * 增量 SSE 解析器（每次订阅一个实例，线程隔离）。
     *
     * <p>按行状态机：{@code feed()} 逐行消费分片，{@code event:} 行开启/切换事件、
     * 空行提交当前事件、{@code data:} 行累积 payload；半包残留在内部 buffer。
     * 兼容两种上游：标准 {@code \n\n} 分隔，以及缺空行、仅以 {@code event:} 换行的写法。
     * {@code flush()} 在流结束时处理残留。</p>
     */
    private static final class SseIncrementalParser {

        private final StringBuilder buffer = new StringBuilder();
        private String currentEvent = "message";
        private final List<String> currentData = new ArrayList<>();
        private boolean inEvent = false;

        List<ServerSentEvent<String>> feed(String chunk) {
            // 统一 CRLF → LF，简化逐行处理
            buffer.append(chunk.replace("\r\n", "\n"));

            List<ServerSentEvent<String>> events = new ArrayList<>();
            int idx;
            while ((idx = buffer.indexOf("\n")) != -1) {
                String line = buffer.substring(0, idx);
                buffer.delete(0, idx + 1);
                ServerSentEvent<String> evt = handleLine(line);
                if (evt != null) {
                    events.add(evt);
                }
            }
            return events;
        }

        List<ServerSentEvent<String>> flush() {
            List<ServerSentEvent<String>> events = new ArrayList<>();
            // 处理最后一行（可能没有换行结尾）
            if (buffer.length() > 0) {
                ServerSentEvent<String> evt = handleLine(buffer.toString());
                if (evt != null) {
                    events.add(evt);
                }
                buffer.setLength(0);
            }
            // 提交未以空行结束的残留事件
            if (inEvent) {
                ServerSentEvent<String> evt = buildEvent();
                resetEvent();
                if (evt != null) {
                    events.add(evt);
                }
            }
            return events;
        }

        private ServerSentEvent<String> handleLine(String line) {
            if (line.startsWith("event:")) {
                // 新事件边界：若上一个事件未以空行结束，先补发
                ServerSentEvent<String> prev = null;
                if (inEvent) {
                    prev = buildEvent();
                }
                currentEvent = line.substring(6).trim();
                currentData.clear();
                inEvent = true;
                return prev;
            }
            if (line.startsWith("data:")) {
                // 按 SSE 规范去掉单个前导空格
                currentData.add(line.substring(5).replaceFirst("^ ", ""));
                inEvent = true;
                return null;
            }
            if (line.isEmpty() && inEvent) {
                // 空行 = 事件结束
                ServerSentEvent<String> evt = buildEvent();
                resetEvent();
                return evt;
            }
            // 注释行（":"）或未知行：忽略
            return null;
        }

        private ServerSentEvent<String> buildEvent() {
            if (currentData.isEmpty()) {
                return null;
            }
            return ServerSentEvent.<String>builder()
                    .event(currentEvent)
                    .data(String.join("\n", currentData))
                    .build();
        }

        private void resetEvent() {
            currentEvent = "message";
            currentData.clear();
            inEvent = false;
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

    private String buildRequestBody(String userId, String role, String message, String sessionId, String traceId) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode body = (com.fasterxml.jackson.databind.node.ObjectNode)
                    objectMapper.createObjectNode()
                    .put("userId", userId)
                    .put("role", role)
                    .put("message", message)
                    .put("traceId", traceId);
            if (sessionId != null && !sessionId.isBlank()) {
                body.put("sessionId", sessionId);
            }
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
