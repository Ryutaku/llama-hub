package com.llama.hub.model;

import lombok.Data;
import java.time.LocalDateTime;


@Data
public class ApiKey {
    private Long id;
    private String name;
    private String keyHash;
    private String keyPrefix;
    private LocalDateTime expiresAt;
    private Long tokenQuota;
    private Long requestQuota;
    private Long tokensUsed= 0L;
    private Long requestsUsed= 0L;
    private Boolean isActive= Boolean.TRUE;
    private LocalDateTime createdAt;
    private LocalDateTime lastUsedAt;
    private Long hitCount= 0L;
    private String keyPlain;
}
