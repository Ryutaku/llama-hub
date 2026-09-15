package com.llama.hub.model;

import lombok.Data;

/** 当日调用聚合统计行 */
@Data
public class TodayStatsVO {
    private long requests;
    private long tokens;
    private long cached;
    private long prompt;
}
