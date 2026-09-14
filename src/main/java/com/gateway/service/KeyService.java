package com.gateway.service;

import com.gateway.model.ApiKey;
import com.gateway.repository.ApiKeyRepository;
import com.gateway.util.ApiKeyUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class KeyService {

    private static final Logger log = LoggerFactory.getLogger(KeyService.class);

    private final ApiKeyRepository apiKeyRepository;

    /** 加密钥（AES），用于存储可重看的明文副本；可通过环境变量覆盖 */
    @Value("${gateway.key.encryption-key:llm-gateway-default}")
    private String encryptionKey;

    public KeyService(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    @Transactional
    public Map<String, Object> create(String name, int number, String unit,
                                      Long tokenQuota, Long requestQuota) {
        String plain = ApiKeyUtil.generate();
        ApiKey key = new ApiKey();
        key.setName(name);
        key.setKeyHash(ApiKeyUtil.sha256Hex(plain));
        key.setKeyPrefix(ApiKeyUtil.prefixOf(plain));
        key.setKeyPlain(ApiKeyUtil.encryptKey(plain, encryptionKey));
        key.setExpiresAt(expireFrom(number, unit, LocalDateTime.now()));
        key.setTokenQuota(validateQuota(tokenQuota));
        key.setRequestQuota(validateQuota(requestQuota));
        key.setIsActive(Boolean.TRUE);
        key.setCreatedAt(LocalDateTime.now());
        key.setTokensUsed(0L);
        key.setRequestsUsed(0L);
        key.setHitCount(0L);
        apiKeyRepository.save(key);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", plain);
        result.put("keyInfo", toDto(key));
        return result;
    }

    @Transactional
    public Map<String, Object> update(Long id, Integer number, String unit,
                                      Boolean isActive, Long tokenQuota, Long requestQuota) {
        ApiKey key = apiKeyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("key not found"));
        if (number != null && unit != null) {
            key.setExpiresAt(expireFrom(number, unit, LocalDateTime.now()));
        }
        if (isActive != null) {
            key.setIsActive(isActive);
        }
        if (tokenQuota != null) {
            key.setTokenQuota(validateQuota(tokenQuota));
        }
        if (requestQuota != null) {
            key.setRequestQuota(validateQuota(requestQuota));
        }
        apiKeyRepository.save(key);
        return toDto(key);
    }

    @Transactional
    public void delete(Long id) {
        apiKeyRepository.deleteById(id);
    }

    /** 解密返回密钥明文副本；未保存明文（老 Key）返回 null */
    @Transactional
    public String reveal(Long id) {
        ApiKey key = apiKeyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("key not found"));
        if (key.getKeyPlain() == null || key.getKeyPlain().isEmpty()) {
            return null;
        }
        return ApiKeyUtil.decryptKey(key.getKeyPlain(), encryptionKey);
    }

    public List<Map<String, Object>> list() {
        return apiKeyRepository.findAll().stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    public ApiKey findById(Long id) {
        return apiKeyRepository.findById(id).orElse(null);
    }

    public String statusOf(ApiKey k) {
        LocalDateTime now = LocalDateTime.now();
        if (k.getExpiresAt() != null && k.getExpiresAt().isBefore(now)) {
            return "expired";
        }
        if ((k.getTokenQuota() != null && k.getTokensUsed() >= k.getTokenQuota())
                || (k.getRequestQuota() != null && k.getRequestsUsed() >= k.getRequestQuota())) {
            return "exceeded";
        }
        if (!Boolean.TRUE.equals(k.getIsActive())) {
            return "disabled";
        }
        return "active";
    }

    public static LocalDateTime expireFrom(int number, String unit, LocalDateTime now) {
        if ("permanent".equalsIgnoreCase(unit)) {
            return null;
        }
        switch (unit.toLowerCase()) {
            case "minute": return now.plusMinutes(number);
            case "hour":   return now.plusHours(number);
            case "day":    return now.plusDays(number);
            case "month":  return now.plusMonths(number);
            case "year":   return now.plusYears(number);
            default:       throw new IllegalArgumentException("unsupported unit: " + unit);
        }
    }

    private Long validateQuota(Long quota) {
        if (quota != null && quota < 0) {
            throw new IllegalArgumentException("quota must be >= 0");
        }
        return quota;
    }

    public Map<String, Object> toDto(ApiKey k) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", k.getId());
        m.put("name", k.getName());
        m.put("keyPrefix", k.getKeyPrefix());
        m.put("expiresAt", k.getExpiresAt());
        m.put("tokenQuota", k.getTokenQuota());
        m.put("requestQuota", k.getRequestQuota());
        m.put("tokensUsed", k.getTokensUsed());
        m.put("requestsUsed", k.getRequestsUsed());
        m.put("isActive", k.getIsActive());
        m.put("createdAt", k.getCreatedAt());
        m.put("lastUsedAt", k.getLastUsedAt());
        m.put("hitCount", k.getHitCount());
        m.put("status", statusOf(k));
        m.put("tokenUsedPct", pct(k.getTokensUsed(), k.getTokenQuota()));
        m.put("requestUsedPct", pct(k.getRequestsUsed(), k.getRequestQuota()));
        return m;
    }

    private Object pct(Long used, Long quota) {
        if (quota == null || quota <= 0) {
            return null;
        }
        return BigDecimal.valueOf(used * 100.0 / quota).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
