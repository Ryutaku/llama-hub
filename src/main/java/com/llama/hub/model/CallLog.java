package com.llama.hub.model;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;


@Data
public class CallLog {
    private Long id;
    private Long keyId;
    private String keyName;
    private String requestId;
    private String endpoint;
    private String model;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Integer cachedTokens;
    private BigDecimal cacheHitRate;
    private BigDecimal tokensPerSec;
    private LocalDateTime startedAt;
    private Long durationMs;
    private Integer statusCode;
    private String errorMsg;
    private String requestBody;
    private String responseBody;
}
