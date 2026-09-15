package com.llama.hub.controller;

import com.llama.hub.model.AuthResult;
import com.llama.hub.model.LoginResult;
import com.llama.hub.model.MeInfo;
import com.llama.hub.service.AdminUserService;
import com.llama.hub.service.AuditService;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
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
    public LoginResult login(@RequestBody Map<String, String> body, HttpServletRequest request,
                             HttpSession session) {
        String username = body.get("username");
        String password = body.get("password");
        String ip = request.getRemoteAddr();

        if (username == null || password == null) {
            auditService.record(username, "LOGIN_FAIL", null, "missing credentials", ip);
            return LoginResult.failure("用户名或密码错误");
        }
        String result = adminUserService.authenticate(username, password);
        if ("LOCKED".equals(result)) {
            auditService.record(username, "LOGIN_FAIL", null, "account locked", ip);
            return LoginResult.locked();
        }
        if (!"OK".equals(result)) {
            auditService.record(username, "LOGIN_FAIL", null, "wrong password", ip);
            return LoginResult.failure("用户名或密码错误");
        }
        session.setAttribute(SESSION_USER, username);
        auditService.record(username, "LOGIN_OK", username, "login success", ip);
        return LoginResult.success(username);
    }

    @GetMapping("/api/me")
    public MeInfo me(HttpSession session) {
        String username = (String) session.getAttribute(SESSION_USER);
        return new MeInfo(username != null, username);
    }

    @PostMapping("/api/logout")
    public AuthResult logout(HttpServletRequest request, HttpSession session) {
        String username = (String) session.getAttribute(SESSION_USER);
        if (username != null) {
            auditService.record(username, "LOGOUT", username, null, request.getRemoteAddr());
        }
        session.invalidate();
        return AuthResult.ok();
    }

    @PutMapping("/api/admin/password")
    public AuthResult changePassword(@RequestBody Map<String, String> body,
                                     HttpServletRequest request, HttpSession session) {
        String username = (String) session.getAttribute(SESSION_USER);
        if (username == null) {
            return AuthResult.failure("未登录");
        }
        String error = adminUserService.changePassword(username, body.get("oldPassword"), body.get("newPassword"));
        if (error != null) {
            auditService.record(username, "PASSWORD_CHANGE", username, "failed: " + error, request.getRemoteAddr());
            return AuthResult.failure(error);
        }
        auditService.record(username, "PASSWORD_CHANGE", username, null, request.getRemoteAddr());
        session.invalidate();
        return AuthResult.ok();
    }
}
