package com.app.campusagent.chat.controller;

import com.app.campusagent.chat.dto.ResumeRequest;
import com.app.campusagent.chat.service.OrchestrationClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * Chat API — SSE 流式聊天端点。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>{@code GET /api/chat/stream}：接收用户消息，从 SecurityContext 提取身份，</li>
 *   <li>转发到编排层并透传 SSE 事件流到前端</li>
 * </ul>
 *
 * <p>安全说明：原始 JWT 仅保存在 {@code Authentication.credentials}（由
 * {@code JwtAuthFilter} 写入），本控制器不落日志、不转发给 Agent。</p>
 *
 * <p>中继说明：{@link OrchestrationClient} 负责增量解码编排层 SSE 流并返回
 * {@code Flux<ServerSentEvent<String>>}，事件名（{@code token} /
 * {@code agent_start} / {@code agent_step} / …）与 data 均已保留。本控制器直接
 * 返回该 Flux，由 Spring MVC 的 {@code ServerSentEventHttpMessageWriter}
 * 自动序列化为标准 SSE 文本（{@code event:…} / {@code data:…} / 空行），
 * 无需手动格式化。</p>
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final OrchestrationClient orchestrationClient;

    public ChatController(OrchestrationClient orchestrationClient) {
        this.orchestrationClient = orchestrationClient;
    }

    /**
     * SSE 流式聊天。
     *
     * @param message 用户消息
     * @param sessionId 可选会话 ID（多轮上下文；前端会话级 UUID）
     * @param traceId 可选追踪 ID
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChat(
            @RequestParam String message,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(required = false) String traceId) {

        // 从 SecurityContext 提取身份（由 JwtAuthFilter 填充）
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String userId = resolveUserId(auth);
        String role = resolveRole(auth);

        String resolvedTraceId = (traceId == null || traceId.isBlank())
                ? UUID.randomUUID().toString() : traceId;

        log.info("Chat stream request: userId={}, role={}, traceId={}", userId, role, resolvedTraceId);

        // 直接返回编排层事件流，由 Spring MVC SSE writer 序列化；
        // 异常时降级为 error 事件，保证流不中断。
        return orchestrationClient.streamChat(userId, role, message, sessionId, resolvedTraceId)
                .onErrorResume(e -> {
                    log.error("Chat stream failed: userId={}, err={}", userId, e.getMessage());
                    return Flux.just(ServerSentEvent.<String>builder()
                            .event("error")
                            .data("{\"message\":\"服务器内部错误\"}")
                            .build());
                });
    }

    /**
     * HITL 确认恢复（SSE 流）：前端确认/取消后调用，编排层以
     * {@code Command(resume=...)} 恢复挂起的 LangGraph 并重调子 Agent。
     *
     * @param request 恢复请求（sessionId 必须与原始 stream 一致 + approved）
     * @param traceId 可选追踪 ID
     */
    @PostMapping(value = "/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> resumeChat(
            @RequestBody ResumeRequest request,
            @RequestParam(required = false) String traceId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String userId = resolveUserId(auth);
        String role = resolveRole(auth);

        String resolvedTraceId = (traceId == null || traceId.isBlank())
                ? UUID.randomUUID().toString() : traceId;

        log.info("Chat resume request: userId={}, sessionId={}, approved={}, traceId={}",
                userId, request.sessionId(), request.approved(), resolvedTraceId);

        return orchestrationClient.resumeChat(
                        userId, role, request.sessionId(), request.approved(), resolvedTraceId)
                .onErrorResume(e -> {
                    log.error("Chat resume failed: userId={}, err={}", userId, e.getMessage());
                    return Flux.just(ServerSentEvent.<String>builder()
                            .event("error")
                            .data("{\"message\":\"服务器内部错误\"}")
                            .build());
                });
    }

    // ──────────────────────────────────────────────────────────────────────
    // 私有方法
    // ──────────────────────────────────────────────────────────────────────

    private String resolveUserId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) {
            return "anonymous";
        }
        Object principal = auth.getPrincipal();
        // JwtAuthFilter 现在注入 domain.User 实体（merge 回归修复后）
        if (principal instanceof com.app.campusagent.domain.User user) {
            return String.valueOf(user.getId());
        }
        if (principal instanceof UserDetails ud) {
            return ud.getUsername();
        }
        return principal.toString();
    }

    private String resolveRole(Authentication auth) {
        if (auth == null || auth.getAuthorities() == null || auth.getAuthorities().isEmpty()) {
            return "UNKNOWN";
        }
        String authority = auth.getAuthorities().iterator().next().getAuthority();
        // 去掉 ROLE_ 前缀：用户 JWT 的 role claim 无前缀（STUDENT/ADMIN/SUPER_ADMIN），
        // 内部契约（Delegation Token / Agent 通道）统一使用无前缀 role
        return authority.startsWith("ROLE_") ? authority.substring("ROLE_".length()) : authority;
    }
}
