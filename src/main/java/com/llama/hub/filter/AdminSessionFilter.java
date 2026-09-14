package com.llama.hub.filter;

import com.llama.hub.controller.AuthController;
import com.llama.hub.service.AdminUserService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@Order(3)
public class AdminSessionFilter extends OncePerRequestFilter {

    private final AdminUserService adminUserService;

    public AdminSessionFilter(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.startsWith("/api/admin/")) {
            HttpSession session = request.getSession(false);
            if (session == null || session.getAttribute(AuthController.SESSION_USER) == null) {
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                response.getOutputStream().write(
                        "{\"error\":{\"message\":\"未登录或会话已过期\",\"type\":\"session_expired\"}}"
                                .getBytes(StandardCharsets.UTF_8));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
