package com.app.campusagent.config;

import com.app.campusagent.domain.User;
import com.app.campusagent.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

@Component
public class AgentDelegationAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AgentDelegationAuthFilter.class);

    private static final String INTERNAL_PREFIX = "/api/internal/lost-found/";
    private static final Duration MAX_TOKEN_LIFETIME = Duration.ofSeconds(60);

    private final UserRepository userRepository;
    private final AgentDelegationReplayStore replayStore;
    private final String sharedSecret;

    public AgentDelegationAuthFilter(
            UserRepository userRepository,
            AgentDelegationReplayStore replayStore,
            @Value("${app.agent.backend-shared-secret:}") String sharedSecret) {
        this.userRepository = userRepository;
        this.replayStore = replayStore;
        this.sharedSecret = sharedSecret;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !request.getRequestURI().startsWith(INTERNAL_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {
        String expectedAction = expectedAction(request);
        String token = bearerToken(request);
        if (!StringUtils.hasText(sharedSecret)) {
            log.warn("AgentDelegationAuthFilter: sharedSecret is empty (app.agent.backend-shared-secret)");
            reject(response);
            return;
        }
        if (sharedSecret.length() < 32) {
            log.warn("AgentDelegationAuthFilter: sharedSecret too short (len={})", sharedSecret.length());
            reject(response);
            return;
        }
        if (expectedAction == null) {
            log.warn("AgentDelegationAuthFilter: expectedAction is null for uri={}", request.getRequestURI());
            reject(response);
            return;
        }
        if (!StringUtils.hasText(token)) {
            log.warn("AgentDelegationAuthFilter: missing bearer token for uri={}", request.getRequestURI());
            reject(response);
            return;
        }

        try {
            SecretKey key = Keys.hmacShaKeyFor(sharedSecret.getBytes(StandardCharsets.UTF_8));
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer("lost-found-agent")
                    .requireAudience("campus-api")
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            Date issuedAt = claims.getIssuedAt();
            Date expiresAt = claims.getExpiration();
            String tokenId = claims.getId();
            String action = claims.get("intended_action", String.class);
            Long userId = parseUserId(claims.getSubject());
            Instant now = Instant.now();
            if (issuedAt == null
                    || expiresAt == null
                    || !StringUtils.hasText(tokenId)
                    || tokenId.length() > 128
                    || userId == null
                    || userId <= 0
                    || !expectedAction.equals(action)
                    || issuedAt.toInstant().isAfter(now.plusSeconds(5))
                    || !expiresAt.toInstant().isAfter(now)
                    || !expiresAt.after(issuedAt)
                    || Duration.between(issuedAt.toInstant(), expiresAt.toInstant())
                    .compareTo(MAX_TOKEN_LIFETIME) > 0
                    || !replayStore.consume(tokenId, expiresAt.toInstant())) {
                log.warn("AgentDelegationAuthFilter: claim validation failed "
                        + "(sub={}, action={} expected={}, expIsAfterNow={}, tokenIdLen={})",
                        userId, action, expectedAction,
                        expiresAt != null && expiresAt.toInstant().isAfter(now),
                        tokenId == null ? -1 : tokenId.length());
                SecurityContextHolder.clearContext();   // 与 catch 分支一致：失败即清理，防残留认证被下游复用
                reject(response);
                return;
            }

            User user = userRepository.findById(userId).orElse(null);
            if (user == null) {
                log.warn("AgentDelegationAuthFilter: user not found for id={}", userId);
                SecurityContextHolder.clearContext();
                reject(response);
                return;
            }
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            user,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_AGENT_LOST_FOUND")));
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (RuntimeException exception) {
            log.warn("AgentDelegationAuthFilter: token verification failed: {}", exception.toString());
            SecurityContextHolder.clearContext();
            reject(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String expectedAction(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI().substring(INTERNAL_PREFIX.length());
        if ("POST".equals(method) && "reports/lost".equals(path)) {
            return "report_lost";
        }
        if ("POST".equals(method) && "reports/found".equals(path)) {
            return "report_found";
        }
        if ("GET".equals(method) && "candidates".equals(path)) {
            return "search_found_items";
        }
        if ("GET".equals(method) && "lost-candidates".equals(path)) {
            return "search_lost_items";
        }
        if ("GET".equals(method) && path.matches("reports/\\d+")) {
            return "get_item_detail";
        }
        if ("POST".equals(method) && path.matches("reports/\\d+/claims")) {
            return "claim_item";
        }
        // L&F 记忆内部 API（chat-memory-requirements §6.2）
        if ("POST".equals(method) && "memory/sessions".equals(path)) {
            return "memory_upsert_session";
        }
        if ("POST".equals(method) && path.matches("memory/sessions/[^/]+/messages/prune")) {
            return "memory_prune_messages";
        }
        if ("POST".equals(method) && path.matches("memory/sessions/[^/]+/messages")) {
            return "memory_append";
        }
        if ("GET".equals(method) && path.matches("memory/sessions/[^/]+")) {
            return "memory_read";
        }
        if ("GET".equals(method) && "memory/users/me".equals(path)) {
            return "memory_read";
        }
        if ("POST".equals(method) && "memory/users/me/facts".equals(path)) {
            return "memory_upsert_fact";
        }
        if ("DELETE".equals(method) && "memory/users/me".equals(path)) {
            return "memory_delete";
        }
        return null;
    }

    private String bearerToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        return StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")
                ? bearer.substring(7)
                : null;
    }

    private Long parseUserId(String subject) {
        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid agent delegation token");
    }
}
