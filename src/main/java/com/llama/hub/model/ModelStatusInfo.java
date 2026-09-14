package com.llama.hub.model;

import lombok.Data;

/** 模型运行状态快照。 */
@Data
public class ModelStatusInfo {
    private String state;
    private Integer pid;
    private boolean portListening;
    private boolean healthOk;
    private boolean sshAvailable;
    private Long uptimeSec;
    private Long lastChangeAt;
    private String message;
}
