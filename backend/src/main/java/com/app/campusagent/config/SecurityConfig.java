package com.app.campusagent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security 配置。
 *
 * <p>安全策略：</p>
 * <ul>
 *   <li>无状态会话（JWT）</li>
 *   <li>{@code /api/chat/**} 必须认证</li>
 *   <li>健康检查 {@code /actuator/health} 放行（供 Docker/K8s 探针）</li>
 *   <li>CORS 仅允许配置的前端来源</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final List<String> allowedOrigins;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter,
                          @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:8080,http://localhost:5173}") List<String> allowedOrigins) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.allowedOrigins = allowedOrigins;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())   // 无状态 JWT，无 CSRF 风险
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // SSE/异步分发不重复鉴权（初始请求已认证，async dispatch 时 SecurityContext 不传播）
                        .dispatcherTypeMatchers(
                                DispatcherType.ASYNC,
                                DispatcherType.ERROR,
                                DispatcherType.FORWARD
                        ).permitAll()
                        // 健康检查 / 认证端点放行
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/api/auth/**").permitAll()
                        // Token Service 内嵌端点：JWKS（公钥，Agent 端验签用）与
                        // token exchange（仅编排层调用，controller 内做 HMAC 校验，无用户 JWT）
                        .requestMatchers("/.well-known/jwks.json", "/internal/token/exchange").permitAll()
                        // Chat API 必须认证
                        .requestMatchers("/api/chat/**").authenticated()
                        // 管理端点必须认证（角色细粒度由方法级 @PreAuthorize 控制）
                        .requestMatchers("/api/admin/**").authenticated()
                        // 其余默认拒绝（新增端点须在此显式声明）
                        .anyRequest().denyAll())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // 允许的来源从配置读取（默认覆盖 vite dev 3000 / 5173 与后端自身 8080）
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
