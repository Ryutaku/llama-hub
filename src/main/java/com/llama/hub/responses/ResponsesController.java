package com.llama.hub.responses;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

/**
 * OpenAI Responses API 入口（精确路径优先于 ProxyController 的 /v1/** 通配）。
 * 网关把 Responses 请求适配为上游 chat/completions，供 Codex 等仅支持 Responses 协议的客户端使用。
 */
@RestController
public class ResponsesController {

    private final ResponsesProxyService responsesProxyService;

    public ResponsesController(ResponsesProxyService responsesProxyService) {
        this.responsesProxyService = responsesProxyService;
    }

    @PostMapping("/v1/responses")
    public CompletableFuture<Void> createResponse(HttpServletRequest request, HttpServletResponse response) {
        return responsesProxyService.handle(request, response);
    }
}