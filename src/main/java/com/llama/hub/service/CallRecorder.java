package com.llama.hub.service;

import com.llama.hub.mapper.ApiKeyMapper;
import com.llama.hub.mapper.CallLogMapper;
import com.llama.hub.model.ApiKey;
import com.llama.hub.model.CallLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 调用日志与配额记账（chat/completions 与 responses 适配层共用）。
 * usage 来源既可以是完整响应 JSON，也可以是流式最后一个含 usage/timings 的 chunk 行。
 */
@Component
@Slf4j
public class CallRecorder {

    private final ObjectMapper objectMapper;
    private final ApiKeyMapper apiKeyMapper;
    private final CallLogMapper callLogMapper;

    public CallRecorder(ObjectMapper objectMapper, ApiKeyMapper apiKeyMapper, CallLogMapper callLogMapper) {
        this.objectMapper = objectMapper;
        this.apiKeyMapper = apiKeyMapper;
        this.callLogMapper = callLogMapper;
    }

    public void record(ApiKey apiKey, String requestId, String endpoint, String model,
                       String requestBodyStr, LocalDateTime startedAt, int status, String errorMsg,
                       String usageSource, String responseBodyStr) {
        try {
            Usage usage = parseUsage(usageSource);
            long durationMs = ChronoUnit.MILLIS.between(startedAt, LocalDateTime.now());
            LocalDateTime now = LocalDateTime.now();

            CallLog callLog = new CallLog();
            callLog.setKeyId(apiKey.getId());
            callLog.setKeyName(apiKey.getName());
            callLog.setRequestId(requestId);
            callLog.setEndpoint(truncate(endpoint, 200));
            callLog.setModel(truncate(model, 100));
            callLog.setStartedAt(startedAt);
            callLog.setDurationMs(durationMs);
            callLog.setStatusCode(status);
            callLog.setErrorMsg(truncate(errorMsg, 500));
            callLog.setRequestBody(requestBodyStr);
            callLog.setResponseBody(responseBodyStr);
            if (usage != null) {
                callLog.setPromptTokens(usage.prompt);
                callLog.setCompletionTokens(usage.completion);
                callLog.setTotalTokens(usage.total);
                callLog.setCachedTokens(usage.cached);
                if (usage.cached != null && usage.prompt != null && usage.prompt > 0) {
                    double rate = usage.cached * 1.0 / usage.prompt;
                    if (rate > 1.0) {
                        rate = 1.0;  // 缓存命中率不可能超过 100%，封顶避免溢出及异常比例
                    }
                    callLog.setCacheHitRate(BigDecimal.valueOf(rate)
                            .setScale(4, RoundingMode.HALF_UP));
                }
                if (usage.completion != null && durationMs > 0) {
                    callLog.setTokensPerSec(BigDecimal.valueOf(usage.completion * 1000.0 / durationMs)
                            .setScale(2, RoundingMode.HALF_UP));
                }
            }
            callLogMapper.insert(callLog);

            long tokensDelta = (usage != null && usage.total != null && usage.total > 0) ? usage.total : 0L;
            apiKeyMapper.incrementUsage(apiKey.getId(), tokensDelta, now);
        } catch (Exception e) {
            log.error("Failed to record call log for request {}", requestId, e);
        }
    }

    public Usage parseUsage(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            String json = s.trim();
            if (json.startsWith("data:")) {
                json = json.substring(5).trim();
            }
            if (json.startsWith("[DONE]") || json.isEmpty()) {
                return null;
            }
            JsonNode root = objectMapper.readTree(json);
            JsonNode usage = root.path("usage");
            JsonNode timings = root.path("timings");
            if (usage.isMissingNode() && timings.isMissingNode()) {
                return null;
            }
            Usage u = new Usage();
            if (!usage.isMissingNode()) {
                if (usage.has("input_tokens") || usage.has("output_tokens")) {
                    // llama.cpp 的 Anthropic 端点把提示词拆成 input_tokens(新增) + cache_read_input_tokens(命中)，
                    // 二者相加才是真实输入；不能把 cache_read 当成 input 的子集（否则缓存占比大时比例爆炸）。
                    Integer inputTokens = intValue(usage.path("input_tokens"));
                    Integer cached = intValue(usage.path("cache_read_input_tokens"));
                    Integer cacheCreated = intValue(usage.path("cache_creation_input_tokens"));
                    u.cached = cached;
                    u.prompt = (inputTokens != null && cached != null)
                            ? inputTokens + cached + (cacheCreated != null ? cacheCreated : 0)
                            : (inputTokens != null ? inputTokens : cached);
                    u.completion = intValue(usage.path("output_tokens"));
                    Integer total = intValue(usage.path("total_tokens"));
                    u.total = total != null ? total
                            : (u.prompt != null && u.completion != null ? u.prompt + u.completion : null);
                } else {
                    u.prompt = intValue(usage.path("prompt_tokens"));
                    u.completion = intValue(usage.path("completion_tokens"));
                    u.total = intValue(usage.path("total_tokens"));
                    JsonNode details = usage.path("prompt_tokens_details");
                    u.cached = intValue(details.path("cached_tokens"));
                }
            } else {
                // llama.cpp 流式响应无 usage，以 timings 兜底（cache_n=缓存命中, prompt_n=实际计算, predicted_n=输出）
                Integer cached = intValue(timings.path("cache_n"));
                Integer promptCalc = intValue(timings.path("prompt_n"));
                Integer predicted = intValue(timings.path("predicted_n"));
                u.cached = cached;
                u.prompt = (cached != null && promptCalc != null) ? cached + promptCalc : (promptCalc != null ? promptCalc : cached);
                u.completion = predicted;
                u.total = (u.prompt != null && u.completion != null) ? u.prompt + u.completion : null;
            }
            return u;
        } catch (Exception e) {
            return null;
        }
    }

    private Integer intValue(JsonNode node) {
        return node.isNumber() ? node.asInt() : null;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    public static class Usage {
        public Integer prompt;
        public Integer completion;
        public Integer total;
        public Integer cached;
    }
}