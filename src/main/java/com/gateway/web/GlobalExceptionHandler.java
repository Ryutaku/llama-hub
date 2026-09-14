package com.gateway.web;

import com.gateway.service.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(errorBody(e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(500).body(errorBody("服务器内部错误"));
    }

    private Map<String, Object> errorBody(String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("message", message);
        err.put("type", "gateway");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", err);
        return body;
    }
}
