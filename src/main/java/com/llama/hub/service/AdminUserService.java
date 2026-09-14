package com.llama.hub.service;

import com.llama.hub.mapper.AdminUserMapper;
import com.llama.hub.model.AdminUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AdminUserService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);
    private static final int MAX_FAILS = 5;
    private static final long LOCK_MINUTES = 5;
    private static final int MIN_PASSWORD_LEN = 8;

    private final AdminUserMapper adminUserMapper;
    private final BCryptPasswordEncoder passwordEncoder;
    private final Map<String, LoginAttempt> attempts = new ConcurrentHashMap<>();

    @Value("${GATEWAY_ADMIN_INITIAL_PASSWORD:admin123}")
    private String initialPassword;

    public AdminUserService(AdminUserMapper adminUserMapper, BCryptPasswordEncoder passwordEncoder) {
        this.adminUserMapper = adminUserMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (adminUserMapper.count() == 0) {
            AdminUser admin = new AdminUser();
            admin.setUsername("admin");
            admin.setPasswordHash(passwordEncoder.encode(initialPassword));
            admin.setCreatedAt(LocalDateTime.now());
            admin.setUpdatedAt(LocalDateTime.now());
            adminUserMapper.insert(admin);
            log.info("Initial admin user created (username: admin), change the password after first login.");
        }
    }

    /** 返回: OK / FAIL / LOCKED */
    public String authenticate(String username, String password) {
        if (username == null || password == null) {
            return "FAIL";
        }
        LoginAttempt attempt = attempts.computeIfAbsent(username, k -> new LoginAttempt(0, 0L));
        synchronized (attempt) {
            long now = System.currentTimeMillis();
            if (attempt.fails >= MAX_FAILS && (now - attempt.lockedAt) < LOCK_MINUTES * 60_000L) {
                return "LOCKED";
            }
            AdminUser user = adminUserMapper.findByUsername(username);
            if (user != null && passwordEncoder.matches(password, user.getPasswordHash())) {
                attempts.remove(username);
                return "OK";
            }
            attempt.fails++;
            attempt.lockedAt = now;
            return "FAIL";
        }
    }

    public boolean isLocked(String username) {
        LoginAttempt attempt = attempts.get(username);
        if (attempt != null) {
            synchronized (attempt) {
                return attempt.fails >= MAX_FAILS
                        && (System.currentTimeMillis() - attempt.lockedAt) < LOCK_MINUTES * 60_000L;
            }
        }
        return false;
    }

    public AdminUser findByUsername(String username) {
        return adminUserMapper.findByUsername(username);
    }

    /** 修改密码。返回 null 表示成功，否则返回错误原因。 */
    public String changePassword(String username, String oldPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LEN) {
            return "新密码长度至少 " + MIN_PASSWORD_LEN + " 位";
        }
        if (newPassword.equals(oldPassword)) {
            return "新密码不能与旧密码相同";
        }
        AdminUser user = adminUserMapper.findByUsername(username);
        if (user == null) {
            return "用户不存在";
        }
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            return "旧密码错误";
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            return "新密码与旧密码一致";
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setUpdatedAt(LocalDateTime.now());
        adminUserMapper.update(user);
        String hash = user.getPasswordHash();
        log.info("Password changed for user {}", username);
        return null;
    }

    private static class LoginAttempt {
        int fails;
        long lockedAt;
        LoginAttempt(int fails, long lockedAt) {
            this.fails = fails;
            this.lockedAt = lockedAt;
        }
    }
}
