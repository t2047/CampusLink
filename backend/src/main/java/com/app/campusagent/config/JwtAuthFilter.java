package com.app.campusagent.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/**
 * JWT 认证过滤器。
 *
 * <p>从 {@code Authorization: Bearer <token>} 提取 JWT，验证签名后
 * 将认证信息写入 SecurityContext：</p>
 * <ul>
 *   <li>{@code principal} → {@link User}（username = 用户 ID）</li>
 *   <li>{@code credentials} → 原始 JWT 字符串（Chat Backend 内部使用，用于签发 Delegation Token）</li>
 *   <li>{@code authorities} → {@code ROLE_<role>}</li>
 * </ul>
 *
 * <p>安全说明：credentials 中的原始 JWT 只在 Chat Backend 内部流转，
 * 不写入日志、不转发给 Agent（Agent 只接收 Delegation Token）。</p>
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private final JwtTokenProvider jwtTokenProvider;

    public JwtAuthFilter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);

        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                Claims claims = jwtTokenProvider.parseToken(token);
                String userId = claims.getSubject();
                String role = claims.get("role", String.class);
                if (role == null || role.isBlank()) {
                    role = "STUDENT";
                }

                // principal = User（username 为 userId）
                User principal = new User(userId, "", Collections.singletonList(
                        new SimpleGrantedAuthority("ROLE_" + role.toUpperCase())));

                // credentials = 原始 JWT（供 Chat Backend 内部签发 Delegation Token）
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                principal,
                                token,   // ← 原始 JWT 存入 credentials
                                principal.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContextHolder.getContext().setAuthentication(authentication);

            } catch (JwtException e) {
                log.debug("JWT validation failed: {}", e.getMessage());
                // 不设置认证，继续走过滤器链（SecurityConfig 会拒绝未认证请求）
            }
        }

        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }
}
