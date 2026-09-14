package com.llama.hub.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getKeyId() { return keyId; }
    public void setKeyId(Long keyId) { this.keyId = keyId; }
    public String getKeyName() { return keyName; }
    public void setKeyName(String keyName) { this.keyName = keyName; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }
    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }
    public Integer getTotalTokens() { return totalTokens; }
    public void setTotalTokens(Integer totalTokens) { this.totalTokens = totalTokens; }
    public Integer getCachedTokens() { return cachedTokens; }
    public void setCachedTokens(Integer cachedTokens) { this.cachedTokens = cachedTokens; }
    public BigDecimal getCacheHitRate() { return cacheHitRate; }
    public void setCacheHitRate(BigDecimal cacheHitRate) { this.cacheHitRate = cacheHitRate; }
    public BigDecimal getTokensPerSec() { return tokensPerSec; }
    public void setTokensPerSec(BigDecimal tokensPerSec) { this.tokensPerSec = tokensPerSec; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public Integer getStatusCode() { return statusCode; }
    public void setStatusCode(Integer statusCode) { this.statusCode = statusCode; }
    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }
    public String getRequestBody() { return requestBody; }
    public void setRequestBody(String requestBody) { this.requestBody = requestBody; }
    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String responseBody) { this.responseBody = responseBody; }
}
