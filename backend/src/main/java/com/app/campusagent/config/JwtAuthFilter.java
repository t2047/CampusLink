package com.app.campusagent.config;

import com.app.campusagent.domain.User;
import com.app.campusagent.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

/**
 * JWT 认证过滤器（用户会话，HS256）。
 *
 * <p>从 {@code Authorization: Bearer <token>} 提取 JWT，验证签名后从数据库加载
 * {@link User} 实体作为 principal —— 业务 Controller 通过
 * {@code @AuthenticationPrincipal User} 直接拿到含 id/email 的完整实体
 * （Lost &amp; Found 的 {@code created_by} 等关联字段依赖此行为，merge 回归修复 2026-08-09）。</p>
 *
 * <p>与 {@link AgentDelegationAuthFilter} 双通道并存：本过滤器只认用户 JWT
 * （用户会话 secret）；Agent 直连通道的 HS256 token 验证失败时不会覆盖
 * AgentDelegationAuthFilter 已设置的认证。</p>
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;

    public JwtAuthFilter(JwtTokenProvider jwtTokenProvider, UserRepository userRepository) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);

        // 已有认证（如 AgentDelegationAuthFilter 设置的）时不覆盖
        if (StringUtils.hasText(token)
                && jwtTokenProvider.validateToken(token)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String email = jwtTokenProvider.getSubjectFromToken(token);
            String role = jwtTokenProvider.getRoleFromToken(token);

            User user = userRepository.findByEmail(email).orElse(null);

            if (user != null && role != null && tokenIssuedAfterPasswordChange(user, token)) {
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                user, null,
                                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }

        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }

    /**
     * 改密失效检查：用户改过密码（passwordChangedAt 非空）且 token 签发时间早于改密时间时，
     * 判定该 token 为改密前的旧 token，拒绝放行（ChangePassWord.md）。
     * 未改过密码或 token 未携带 iat 时保持原有行为。
     *
     * <p>JWT 的 iat 按规范是秒级精度（NumericDate），而 passwordChangedAt 是微秒级，
     * 故把改密时间对齐到秒再比较——避免同一秒内新签发的 token 因毫秒差被误拒；
     * 代价是同一秒内（改密前）签发的 token 最多残留有效 1 秒，相比原 24 小时窗口可忽略。</p>
     */
    private boolean tokenIssuedAfterPasswordChange(User user, String token) {
        LocalDateTime passwordChangedAt = user.getPasswordChangedAt();
        if (passwordChangedAt == null) {
            return true;
        }
        Date issuedAt = jwtTokenProvider.getIssuedAtFromToken(token);
        if (issuedAt == null) {
            return true;
        }
        LocalDateTime changed = passwordChangedAt.truncatedTo(ChronoUnit.SECONDS);
        return !changed.atZone(ZoneId.systemDefault())
                .toInstant()
                .isAfter(issuedAt.toInstant());
    }
}
