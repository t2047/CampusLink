package com.app.campusagent.config;

import com.app.campusagent.facilities.security.FacilityMcpDelegationAuthFilter;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security 配置（Spring Security 7 / Spring Boot 4）。
 *
 * <p>两条鉴权链并存：</p>
 * <ul>
 *   <li>{@link JwtAuthFilter}：用户 JWT（HS256，无状态）→ 保护 Chat / Admin / Lost &amp; Found API</li>
 *   <li>{@link AgentDelegationAuthFilter}：Agent 服务间 HS256 Delegation 通道
 *       （组员实现，保护 /api/internal/lost-found/**，角色 AGENT_LOST_FOUND）</li>
 * </ul>
 *
 * <p>Token Service 内嵌端点放行：/.well-known/jwks.json（公钥，Agent 端验签）与
 * /internal/token/exchange（仅编排层调用，controller 内 HMAC 校验，无用户 JWT）。</p>
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final AgentDelegationAuthFilter agentDelegationAuthFilter;
    private final FacilityMcpDelegationAuthFilter facilityMcpDelegationAuthFilter;
    private final List<String> allowedOrigins;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public SecurityConfig(
            JwtAuthFilter jwtAuthFilter,
            AgentDelegationAuthFilter agentDelegationAuthFilter,
            FacilityMcpDelegationAuthFilter facilityMcpDelegationAuthFilter,
            @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:8080,http://localhost:5173}") String allowedOrigins) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.agentDelegationAuthFilter = agentDelegationAuthFilter;
        this.facilityMcpDelegationAuthFilter = facilityMcpDelegationAuthFilter;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // CSRF 不适用于本项目：认证凭证完全走无状态 Bearer JWT（Authorization
                // header），不使用 cookie/session 会话——前端 web 将 token 存于
                // sessionStorage、安卓存于 SessionStore，请求时由客户端注入 header，
                // 浏览器不会自动携带该凭证，因此不存在 CSRF 攻击路径。
                // 若未来改为 httpOnly cookie 承载 token，需重新评估并启用 CSRF。
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(
                        (request, response, authException) -> response.sendError(
                                HttpServletResponse.SC_UNAUTHORIZED,
                                "Unauthorized")))
                .authorizeHttpRequests(auth -> auth
                        // SSE/异步分发不重复鉴权（初始请求已认证，async dispatch 时 SecurityContext 不传播）
                        .dispatcherTypeMatchers(
                                DispatcherType.ASYNC,
                                DispatcherType.ERROR,
                                DispatcherType.FORWARD
                        ).permitAll()
                        // 健康检查 / 认证端点放行
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/api/auth/**").permitAll()
                        // 图片回显代理端点放行：<img> 标签不携带 JWT，物品照片敏感度低。
                        // 已知权衡：imageId 为自增主键可枚举，无登录可看图（见 IMAGE_MATCHING_DEVELOPMENT §6 开放决策）
                        .requestMatchers("/api/lost-found/images/**").permitAll()
                        // 头像回显代理端点放行：对象键为随机 UUID（avatar-{uuid}.{ext}），
                        // 不可枚举；同物品图片代理，允许无登录看图（个人中心需求 §9.3）
                        .requestMatchers("/api/users/avatar/**").permitAll()
                        // Token Service 内嵌端点：JWKS（公钥，Agent 端验签用）与
                        // token exchange（仅编排层调用，controller 内做 HMAC 校验，无用户 JWT）
                        .requestMatchers("/.well-known/jwks.json", "/internal/token/exchange").permitAll()
                        // Agent 服务间通道（组员自研 Lost & Found 直连鉴权）
                        .requestMatchers("/api/internal/lost-found/**")
                        .hasRole("AGENT_LOST_FOUND")
                        // Chat API 必须认证
                        .requestMatchers("/api/chat/**").authenticated()
                        // 管理端点（角色细粒度由方法级 @PreAuthorize 控制）
                        .requestMatchers("/api/admin/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
                        // 其余要求登录（Lost & Found 业务端点等）
                        .anyRequest().authenticated()
                )
                .addFilterBefore(agentDelegationAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(facilityMcpDelegationAuthFilter, JwtAuthFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
