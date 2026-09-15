package com.llama.hub.model;

import lombok.Data;

import java.math.BigDecimal;

/** 按 Key 聚合的用量统计行 */
@Data
public class UsageRowVO {
    private Long keyId;
    private String name;
    private Long count;
    private Long prompt;
    private Long completion;
    private Long total;
    private Long cached;
    private BigDecimal avgSpeed;
    private BigDecimal avgDuration;
    private Long errors;
}
