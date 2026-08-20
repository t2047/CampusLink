/**
 * 失物招领（Lost & Found）模块的「全局异常处理器」。
 *
 * <p>通过 {@link RestControllerAdvice} 只作用于本模块（{@code basePackages =
 * "com.app.campusagent.lostfound"}）的控制器，把业务异常、上传大小超限、
 * 参数校验失败等统一转换为一致的 JSON 错误响应结构。</p>
 */
package com.app.campusagent.lostfound.exception;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模块级全局异常处理器。
 *
 * <p>所有错误响应体统一为 {@code {"timestamp", "code", "error"}} 结构（见 {@link #error}），
 * 便于前端统一解析。标有 {@code @Order(Ordered.HIGHEST_PRECEDENCE)} 以确保本处理器在
 * 与其它全局处理器共存时拥有最高优先级，优先处理本模块抛出的异常。</p>
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.app.campusagent.lostfound")
public class LostFoundExceptionHandler {

    /**
     * 处理本模块抛出的业务异常 {@link LostFoundApiException}。
     *
     * <p>异常本身已携带 HTTP 状态码与业务错误码，这里直接透传：以
     * {@code ex.getStatus()} 作为响应状态码，消息作为响应体。</p>
     *
     * @param ex 被捕获的业务异常
     * @return 携带对应 HTTP 状态码与 {@code {timestamp, code, error}} 结构的响应体
     */
    @ExceptionHandler(LostFoundApiException.class)
    ResponseEntity<Map<String, Object>> handleDomain(LostFoundApiException ex) {
        return ResponseEntity.status(ex.getStatus()).body(error(ex.getCode(), ex.getMessage()));
    }

    /**
     * 处理上传文件超过大小限制的异常 {@link MaxUploadSizeExceededException}。
     *
     * <p>统一返回 413 Payload Too Large，并固定业务错误码 {@code IMAGE_TOO_LARGE}
     * 与 "10 MB 或更小" 的提示文案，向客户端说明图片大小约束。</p>
     *
     * @param ex 被捕获的上传超限异常（当前仅用于匹配异常类型，未读取其内部信息）
     * @return HTTP 413 与固定错误码的错误响应
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, Object>> handleUploadSize(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(error("IMAGE_TOO_LARGE", "Each image must be 10 MB or smaller"));
    }

    /**
     * 处理 Spring 的请求体参数校验异常 {@link MethodArgumentNotValidException}。
     *
     * <p>遍历 {@code BindingResult} 中的字段错误，把每个字段名映射到对应的校验失败
     * 提示语，组装进 {@code fieldErrors} 子结构；整体响应状态为 422 Unprocessable Entity。
     * 返回结构示例：{@code {timestamp, code, error, fieldErrors: {字段名: 提示语}}}。</p>
     *
     * @param ex 被捕获的校验异常
     * @return HTTP 422，附带字段级错误明细的错误响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(field -> fields.put(field.getField(), field.getDefaultMessage()));
        Map<String, Object> body = error("VALIDATION_FAILED", "One or more fields are invalid");
        body.put("fieldErrors", fields);
        return ResponseEntity.unprocessableEntity().body(body);
    }

    /**
     * 组装统一错误响应体。
     *
     * <p>使用 {@link LinkedHashMap} 保证字段顺序固定：依次为
     * {@code timestamp}（当前时间戳）、{@code code}（业务错误码）、
     * {@code error}（错误描述）。</p>
     *
     * @param code    业务错误码
     * @param message 错误描述
     * @return 统一的错误响应体 Map
     */
    private Map<String, Object> error(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("code", code);
        body.put("error", message);
        return body;
    }
}
