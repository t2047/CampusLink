/**
 * 失物招领（Lost & Found）模块的「业务异常」。
 *
 * <p>用于把业务层校验失败（如报告不存在、无权操作、重复认领等）包装成携带
 * HTTP 状态码与业务错误码的异常向上抛出，最终由 {@link LostFoundExceptionHandler}
 * 统一捕获并转换为标准 JSON 错误响应体返回给前端。</p>
 */
package com.app.campusagent.lostfound.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 业务异常类。
 *
 * <p>继承 {@link RuntimeException}（非受检异常），使业务层无需在每个方法上声明
 * {@code throws}，异常即可自由向上抛到全局异常处理器，简化调用链。</p>
 *
 * <p>相比原生异常，额外携带两个字段：</p>
 * <ul>
 *   <li>{@link #status}：期望返回给客户端的 HTTP 状态码（如 400 / 403 / 404）；</li>
 *   <li>{@link #code}：稳定的业务错误码字符串（如 {@code REPORT_NOT_FOUND}），
 *       客户端可据此做程序化判断，而不依赖可能变动的人性化文案。</li>
 * </ul>
 *
 * <p>字段由 {@link Getter} 注解自动生成只读 getter（字段声明为 {@code final}）。</p>
 */
@Getter
public class LostFoundApiException extends RuntimeException {

    /** 期望返回给客户端的 HTTP 状态码。 */
    private final HttpStatus status;

    /** 稳定的业务错误码字符串，供客户端程序化识别错误类型。 */
    private final String code;

    /**
     * 构造业务异常。
     *
     * @param status  HTTP 状态码
     * @param code    业务错误码
     * @param message 面向用户的错误描述（作为异常消息）
     */
    public LostFoundApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /**
     * 构造业务异常（带底层原因）。
     *
     * <p>适用于底层已捕获异常、需要包装为业务异常向上传递的场景，
     * 保留原始 {@link Throwable} 以便日志追踪根因。</p>
     *
     * @param status  HTTP 状态码
     * @param code    业务错误码
     * @param message 面向用户的错误描述
     * @param cause   底层异常原因
     */
    public LostFoundApiException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }
}
