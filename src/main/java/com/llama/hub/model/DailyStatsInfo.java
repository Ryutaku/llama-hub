package com.llama.hub.model;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 按日聚合的每日用量统计（缺失日期补零） */
@Data
public class DailyStatsInfo {
    private List<Item> items;
    private Total total;

    /** 单日用量统计项 */
    @Data
    public static class Item {
        private String date;
        private long count;
        private long promptTokens;
        private long completionTokens;
        private long totalTokens;
        private long cachedTokens;
        private BigDecimal cacheHitRate;
        private long errors;
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
        private long errors;
    }
}