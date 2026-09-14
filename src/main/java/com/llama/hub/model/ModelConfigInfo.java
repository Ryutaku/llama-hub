package com.llama.hub.model;

import lombok.Data;

/** 当前模型配置视图（含运行进程对照）。 */
@Data
public class ModelConfigInfo {
    private String args;
    private String env;
    private String source;
    private String updatedAt;
    private String runningArgs;
    private String runningEnv;
    private Boolean drift;
}
