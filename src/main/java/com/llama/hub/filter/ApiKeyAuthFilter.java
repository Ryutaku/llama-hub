package com.llama.hub.filter;

import com.llama.hub.model.ApiKey;
import com.llama.hub.repository.ApiKeyRepository;
import com.llama.hub.util.ApiKeyUtil;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDateTime;

public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String ATTR_API_KEY = "gw_api_key";

    private final ApiKeyRepository apiKeyRepository;

    public ApiKeyAuthFilter(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            writeJsonError(response, 401, "missing api key", "invalid_request_error");
            return;
        }
        String plain = header.substring(7).trim();
        if (plain.isEmpty()) {
            writeJsonError(response, 401, "missing api key", "invalid_request_error");
            return;
        }
        ApiKey key = apiKeyRepository.findByKeyHash(ApiKeyUtil.sha256Hex(plain));
        if (key == null) {
            writeJsonError(response, 401, "invalid api key", "invalid_request_error");
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (key.getExpiresAt() != null && key.getExpiresAt().isBefore(now)) {
            writeJsonError(response, 401, "api key expired", "invalid_request_error");
            return;
        }
        if (!Boolean.TRUE.equals(key.getIsActive())) {
            writeJsonError(response, 401, "api key disabled", "invalid_request_error");
            return;
        }
        if ((key.getTokenQuota() != null && key.getTokensUsed() >= key.getTokenQuota())
                || (key.getRequestQuota() != null && key.getRequestsUsed() >= key.getRequestQuota())) {
            writeJsonError(response, 429, "quota exceeded", "quota_exceeded");
            return;
        }
        request.setAttribute(ATTR_API_KEY, key);
        filterChain.doFilter(request, response);
    }

    public static void writeJsonError(HttpServletResponse response, int status, String message, String type)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":{\"message\":\"" + escape(message)
                + "\",\"type\":\"" + type + "\"}}");
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
