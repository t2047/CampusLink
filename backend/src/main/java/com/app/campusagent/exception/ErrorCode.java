package com.app.campusagent.exception;

/**
 * 业务错误码。
 *
 * <p>统一错误码定义，供 {@link BusinessException} 使用。</p>
 */
public enum ErrorCode {

    /** 通用业务错误（默认）。 */
    BUSINESS_ERROR("BUSINESS_ERROR", "业务处理失败"),

    /** JWT 无效或已过期。 */
    INVALID_TOKEN("INVALID_TOKEN", "登录状态无效或已过期"),

    /** 资源不存在。 */
    NOT_FOUND("NOT_FOUND", "资源不存在"),

    /** 无权限访问。 */
    ACCESS_DENIED("ACCESS_DENIED", "无权访问"),

    /** 参数校验失败。 */
    VALIDATION_ERROR("VALIDATION_ERROR", "参数校验失败"),

    /** 昵称缺失（个人中心需求 §11.2）。 */
    NICKNAME_REQUIRED("NICKNAME_REQUIRED", "昵称不能为空"),

    /** 昵称长度超限（个人中心需求 §11.2：去除首尾空白后 1-30 字符）。 */
    NICKNAME_INVALID_LENGTH("NICKNAME_INVALID_LENGTH", "昵称长度须为 1-30 个字符"),

    /** 修改密码：当前密码或新密码为空。 */
    PASSWORD_REQUIRED("PASSWORD_REQUIRED", "密码不能为空"),

    /** 修改密码：新密码长度超限（至少 6 字符，且 UTF-8 不超过 72 字节，BCrypt 上限）。 */
    PASSWORD_INVALID_LENGTH("PASSWORD_INVALID_LENGTH", "新密码长度须为 6 个字符以上，且不超过 72 字节"),

    /** 修改密码：当前密码与库中不一致。 */
    PASSWORD_CURRENT_INCORRECT("PASSWORD_CURRENT_INCORRECT", "当前密码不正确"),

    /** 修改密码：新密码与当前密码相同。 */
    PASSWORD_SAME_AS_CURRENT("PASSWORD_SAME_AS_CURRENT", "新密码不能与当前密码相同");

    private final String code;
    private final String defaultMessage;

    ErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public String getCode() {
        return code;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
