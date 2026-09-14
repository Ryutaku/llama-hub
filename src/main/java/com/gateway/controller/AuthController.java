package com.gateway.controller;

import com.gateway.service.AdminUserService;
import com.gateway.service.AuditService;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class AuthController {

    public static final String SESSION_USER = "gw_user";

    private final AdminUserService adminUserService;
    private final AuditService auditService;

    public AuthController(AdminUserService adminUserService, AuditService auditService) {
        this.adminUserService = adminUserService;
        this.auditService = auditService;
    }

    @PostMapping("/api/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body, HttpServletRequest request,
                                     HttpSession session) {
        String username = body.get("username");
        String password = body.get("password");
        String ip = request.getRemoteAddr();

        Map<String, Object> m = new LinkedHashMap<>();
        if (username == null || password == null) {
            auditService.record(username, "LOGIN_FAIL", null, "missing credentials", ip);
            m.put("ok", false);
            m.put("error", "用户名或密码错误");
            return m;
        }
        String result = adminUserService.authenticate(username, password);
        if ("LOCKED".equals(result)) {
            auditService.record(username, "LOGIN_FAIL", null, "account locked", ip);
            m.put("ok", false);
            m.put("error", "连续失败次数过多，请5分钟后再试");
            m.put("locked", true);
            return m;
        }
        if (!"OK".equals(result)) {
            auditService.record(username, "LOGIN_FAIL", null, "wrong password", ip);
            m.put("ok", false);
            m.put("error", "用户名或密码错误");
            return m;
        }
        session.setAttribute(SESSION_USER, username);
        auditService.record(username, "LOGIN_OK", username, "login success", ip);
        m.put("ok", true);
        m.put("username", username);
        return m;
    }

    @GetMapping("/api/me")
    public Map<String, Object> me(HttpSession session) {
        Map<String, Object> m = new LinkedHashMap<>();
        String username = (String) session.getAttribute(SESSION_USER);
        m.put("ok", username != null);
        m.put("username", username);
        return m;
    }

    @PostMapping("/api/logout")
    public Map<String, Object> logout(HttpServletRequest request, HttpSession session) {
        String username = (String) session.getAttribute(SESSION_USER);
        if (username != null) {
            auditService.record(username, "LOGOUT", username, null, request.getRemoteAddr());
        }
        session.invalidate();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }

    @PutMapping("/api/admin/password")
    public Map<String, Object> changePassword(@RequestBody Map<String, String> body,
                                              HttpServletRequest request, HttpSession session) {
        String username = (String) session.getAttribute(SESSION_USER);
        Map<String, Object> m = new LinkedHashMap<>();
        if (username == null) {
            m.put("ok", false);
            m.put("error", "未登录");
            return m;
        }
        String error = adminUserService.changePassword(username, body.get("oldPassword"), body.get("newPassword"));
        if (error != null) {
            auditService.record(username, "PASSWORD_CHANGE", username, "failed: " + error, request.getRemoteAddr());
            m.put("ok", false);
            m.put("error", error);
            return m;
        }
        auditService.record(username, "PASSWORD_CHANGE", username, null, request.getRemoteAddr());
        session.invalidate();
        m.put("ok", true);
        return m;
    }
}
