package com.llama.hub.model;

import lombok.Data;
import java.util.List;

/** 从运行进程快照的结果（含只读运行事实）。 */
@Data
public class ModelSnapshotResult {
    private String args;
    private String env;
    private String source;
    private List<DiffItem> diff;
    private List<String> runtimeFacts;
}
