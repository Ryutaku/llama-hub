package com.gateway.filter;

import com.gateway.config.GatewayProperties;
import com.gateway.util.IpUtil;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
@Order(1)
public class IpWhitelistFilter extends OncePerRequestFilter {

    private final List<String> rules = new ArrayList<>();

    public IpWhitelistFilter(GatewayProperties properties) {
        String allowed = properties.getAdmin().getAllowedIps();
        if (allowed != null) {
            for (String rule : allowed.split(",")) {
                String trimmed = rule.trim();
                if (!trimmed.isEmpty()) {
                    rules.add(trimmed);
                }
            }
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean adminPath = path.equals("/")
                || path.equals("/api/login")
                || path.equals("/api/logout")
                || path.startsWith("/api/admin/");
        if (adminPath && !rules.isEmpty() && !allowed(IpUtil.ip(request))) {
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getOutputStream().write(
                    "{\"error\":{\"message\":\"当前 IP 不允许访问管理端\",\"type\":\"ip_blocked\"}}"
                            .getBytes(StandardCharsets.UTF_8));
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean allowed(String ip) {
        for (String rule : rules) {
            if (matches(ip, rule)) {
                return true;
            }
        }
        return false;
    }

    /** 支持精确 IP 与 IPv4 CIDR；IPv6 仅精确匹配 */
    private static boolean matches(String ip, String rule) {
        if (rule.contains("/")) {
            int slash = rule.indexOf('/');
            String base = rule.substring(0, slash);
            int bits;
            try {
                bits = Integer.parseInt(rule.substring(slash + 1).trim());
            } catch (NumberFormatException e) {
                return false;
            }
            long a = ipToLong(ip);
            long b = ipToLong(base);
            if (a < 0 || b < 0 || bits < 0 || bits > 32) {
                return false;
            }
            long mask = bits == 0 ? 0 : (0xFFFFFFFFL << (32 - bits)) & 0xFFFFFFFFL;
            return (a & mask) == (b & mask);
        }
        return rule.equals(ip);
    }

    private static long ipToLong(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return -1;
        }
        long result = 0;
        for (String part : parts) {
            int value;
            try {
                value = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                return -1;
            }
            if (value < 0 || value > 255) {
                return -1;
            }
            result = (result << 8) | value;
        }
        return result;
    }
}
