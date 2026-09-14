package com.gateway.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "api_key", indexes = {
        @Index(name = "idx_api_key_hash", columnList = "key_hash", unique = true)
})
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "key_hash", nullable = false, length = 64)
    private String keyHash;

    @Column(name = "key_prefix", nullable = false, length = 16)
    private String keyPrefix;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "token_quota")
    private Long tokenQuota;

    @Column(name = "request_quota")
    private Long requestQuota;

    @Column(name = "tokens_used", nullable = false)
    private Long tokensUsed = 0L;

    @Column(name = "requests_used", nullable = false)
    private Long requestsUsed = 0L;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = Boolean.TRUE;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "hit_count", nullable = false)
    private Long hitCount = 0L;

    @Column(name = "key_plain", columnDefinition = "TEXT")
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
