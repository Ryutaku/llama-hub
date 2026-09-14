package com.llama.hub.model;

import lombok.Data;
import java.util.List;

/** 配置保存/版本应用的结果。 */
@Data
public class ConfigSaveResult {
    private String args;
    private String env;
    private String source;
    private List<DiffItem> diff;
    /** 应用并启动时返回 STARTING，纯保存/应用为 null。 */
    private String state;
}
