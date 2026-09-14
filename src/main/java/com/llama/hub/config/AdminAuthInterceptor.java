package com.llama.hub.config;

import com.llama.hub.controller.AuthController;
import com.llama.hub.util.IpMatcher;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * 管理端拦截器：
 * 1) IP 白名单（配置非空时生效，作用于管理页面与 /api/*）；
 * 2) Session 认证（/api/login 除外）。
 */
public class AdminAuthInterceptor implements HandlerInterceptor {

    private final String allowedIps;

    public AdminAuthInterceptor(String allowedIps) {
        this.allowedIps = allowedIps;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String ip = request.getRemoteAddr();
        if (!IpMatcher.matches(ip, allowedIps)) {
            writeJson(response, 403, "forbidden");
            return false;
        }
        String path = request.getRequestURI();
        if (!path.startsWith("/api/") || "/api/login".equals(path)) {
            // 静态页面（SPA）或登录接口：仅受 IP 白名单约束
            return true;
        }
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(AuthController.SESSION_USER) == null) {
            writeJson(response, 401, "unauthorized");
            return false;
        }
        return true;
    }

    private void writeJson(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
