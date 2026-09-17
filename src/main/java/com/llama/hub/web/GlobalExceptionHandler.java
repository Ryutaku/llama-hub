package com.llama.hub.web;

import lombok.extern.slf4j.Slf4j;
import com.llama.hub.model.ApiErrorResult;
import com.llama.hub.service.ApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {


    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResult> handleApi(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(ApiErrorResult.of("gateway", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResult> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(400).body(ApiErrorResult.of("invalid_request", e.getMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResult> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(404).body(ApiErrorResult.of("not_found", "资源不存在"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResult> handleOther(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(500).body(ApiErrorResult.of("gateway", "服务器内部错误"));
    }
}