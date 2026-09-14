package com.gateway.controller;

import com.gateway.service.ProxyService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.CompletableFuture;

@RestController
public class ProxyController {

    private final ProxyService proxyService;

    public ProxyController(ProxyService proxyService) {
        this.proxyService = proxyService;
    }

    @RequestMapping(value = "/v1/**")
    public CompletableFuture<Void> proxy(HttpServletRequest request, HttpServletResponse response) {
        return proxyService.proxy(request, response);
    }
}
