package com.llama.hub.model;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 仪表盘数据：今日统计 + 近 7 天趋势 + Top 5 Key + 上游状态 */
@Data
public class DashboardInfo {
    private long todayRequests;
    private long todayTokens;
    private BigDecimal todayCacheHitRate;
    private long activeKeys;
    private List<TrendPoint> trend;
    private List<TopKey> topKeys;
    private UpstreamStatusInfo upstream;

    /** 近 7 天单日趋势点 */
    @Data
    public static class TrendPoint {
        private String date;
        private String dateFull;
        private long count;
        private long tokens;
        private long promptTokens;
        private long completionTokens;
        private long cachedTokens;
    }

    /** 今日活跃 Key 排行项 */
    @Data
    public static class TopKey {
        private Long keyId;
        private String name;
        private Long count;
        private Long tokens;
    }
}
