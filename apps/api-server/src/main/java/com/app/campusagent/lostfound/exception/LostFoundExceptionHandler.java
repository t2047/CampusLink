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

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.app.campusagent.lostfound")
public class LostFoundExceptionHandler {

    @ExceptionHandler(LostFoundApiException.class)
    ResponseEntity<Map<String, Object>> handleDomain(LostFoundApiException ex) {
        return ResponseEntity.status(ex.getStatus()).body(error(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, Object>> handleUploadSize(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(error("IMAGE_TOO_LARGE", "Each image must be 10 MB or smaller"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(field -> fields.put(field.getField(), field.getDefaultMessage()));
        Map<String, Object> body = error("VALIDATION_FAILED", "One or more fields are invalid");
        body.put("fieldErrors", fields);
        return ResponseEntity.unprocessableEntity().body(body);
    }

    private Map<String, Object> error(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("code", code);
        body.put("error", message);
        return body;
    }
}
