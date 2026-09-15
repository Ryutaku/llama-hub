package com.llama.hub.model;

import lombok.Data;

import java.time.LocalDate;

/** 按日聚合的趋势行 */
@Data
public class TrendRowVO {
    private LocalDate d;
    private long cnt;
    private long tok;
    private long prompt;
    private long comp;
    private long cached;
}
