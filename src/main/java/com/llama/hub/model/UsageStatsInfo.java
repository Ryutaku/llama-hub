package com.llama.hub.model;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 按 Key + 日期区间的 token 用量统计 */
@Data
public class UsageStatsInfo {
    private List<Item> items;
    private Total total;

    /** 单个 Key 的用量统计项 */
    @Data
    public static class Item {
        private Long keyId;
        private String name;
        private Long count;
        private Long promptTokens;
        private Long completionTokens;
        private Long totalTokens;
        private Long cachedTokens;
        private BigDecimal cacheHitRate;
        private BigDecimal avgSpeed;
        private BigDecimal avgDuration;
        private Long errors;
    }

    /** 汇总行 */
    @Data
    public static class Total {
        private long count;
        private long promptTokens;
        private long completionTokens;
        private long totalTokens;
        private long cachedTokens;
        private BigDecimal cacheHitRate;
    }
}
