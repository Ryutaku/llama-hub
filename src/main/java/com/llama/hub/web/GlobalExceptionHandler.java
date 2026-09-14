package com.llama.hub.web;

import lombok.extern.slf4j.Slf4j;
import com.llama.hub.service.ApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {


    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(errorBody(e.getMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(404).body(errorBody("资源不存在"));
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
