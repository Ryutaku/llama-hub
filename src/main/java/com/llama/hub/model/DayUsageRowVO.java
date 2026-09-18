package com.llama.hub.model;

import lombok.Data;

import java.time.LocalDate;

/** 按日聚合的用量统计行 */
@Data
public class DayUsageRowVO {
    private LocalDate d;
    private Long count;
    private Long prompt;
    private Long completion;
    private Long total;
    private Long cached;
    private Long errors;
}