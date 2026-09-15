package com.llama.hub.model;

import lombok.Data;

/** 参数/环境变量 diff 项（old 或 newVal 为 null 表示另一侧不存在）。JSON 键为 newVal，前端按 newVal 读取。 */
@Data
public class DiffItem {
    /** arg=启动参数 env=环境变量 */
    private String kind;
    private String flag;
    private String old;
    private String newVal;
}
