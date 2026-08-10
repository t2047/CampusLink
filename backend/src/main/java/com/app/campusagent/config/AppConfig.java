package com.app.campusagent.config;

import org.springframework.context.annotation.Configuration;

/**
 * 应用配置。
 *
 * <p>注意：{@code PasswordEncoder} Bean 已在 {@link SecurityConfig} 中定义，
 * 此处不再重复声明（Spring Boot 3.4 默认 {@code allow-bean-definition-overriding=false}，
 * 同名 Bean 会导致启动失败）。</p>
 */
@Configuration
public class AppConfig {
}
