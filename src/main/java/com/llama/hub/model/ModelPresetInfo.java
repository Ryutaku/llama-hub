package com.llama.hub.model;

import lombok.Data;

/** 参数版本视图（列表/保存/改名接口返回）。 */
@Data
public class ModelPresetInfo {
    private Long id;
    private String name;
    private String note;
    private String source;
    private String createdAt;
    private String args;
    private String env;
    private Boolean isCurrent;
    private Boolean isRunning;
}
