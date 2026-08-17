package com.app.campusagent.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 密码长度约束，委托 {@link com.app.campusagent.util.PasswordRules}：
 * 至少 6 个字符，且 UTF-8 编码不超过 72 字节（BCrypt 上限）。
 * 用于注册 / 管理员创建用户等 Bean Validation DTO。
 */
@Documented
@Constraint(validatedBy = PasswordValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPassword {

    String message() default "密码须为 6 个字符以上，且不超过 72 字节";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
