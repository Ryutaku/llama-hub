package com.llama.hub.model;

import lombok.Data;

import java.time.LocalDateTime;

/** API Key 列表/创建/更新返回的视图对象 */
@Data
public class KeyInfo {
    private Long id;
    private String name;
    private String keyPrefix;
    private LocalDateTime expiresAt;
    private Long tokenQuota;
    private Long requestQuota;
    private Long tokensUsed;
    private Long requestsUsed;
    private Boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime lastUsedAt;
    private Long hitCount;
    private String status;
    private Double tokenUsedPct;
    private Double requestUsedPct;
}
