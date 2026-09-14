package com.llama.hub.model;

import java.time.LocalDateTime;

public class ApiKey {

    private Long id;

    private String name;

    private String keyHash;

    private String keyPrefix;

    private LocalDateTime expiresAt;

    private Long tokenQuota;

    private Long requestQuota;

    private Long tokensUsed = 0L;

    private Long requestsUsed = 0L;

    private Boolean isActive = Boolean.TRUE;

    private LocalDateTime createdAt;

    private LocalDateTime lastUsedAt;

    private Long hitCount = 0L;

    private String keyPlain;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getKeyHash() { return keyHash; }
    public void setKeyHash(String keyHash) { this.keyHash = keyHash; }
    public String getKeyPrefix() { return keyPrefix; }
    public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public Long getTokenQuota() { return tokenQuota; }
    public void setTokenQuota(Long tokenQuota) { this.tokenQuota = tokenQuota; }
    public Long getRequestQuota() { return requestQuota; }
    public void setRequestQuota(Long requestQuota) { this.requestQuota = requestQuota; }
    public Long getTokensUsed() { return tokensUsed; }
    public void setTokensUsed(Long tokensUsed) { this.tokensUsed = tokensUsed; }
    public Long getRequestsUsed() { return requestsUsed; }
    public void setRequestsUsed(Long requestsUsed) { this.requestsUsed = requestsUsed; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public Long getHitCount() { return hitCount; }
    public void setHitCount(Long hitCount) { this.hitCount = hitCount; }
    public String getKeyPlain() { return keyPlain; }
    public void setKeyPlain(String keyPlain) { this.keyPlain = keyPlain; }
}
