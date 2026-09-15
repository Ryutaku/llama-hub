package com.llama.hub.model;

import lombok.Data;

/** 创建 Key 的返回：明文密钥（仅此一次）+ Key 信息 */
@Data
public class KeyCreateResult {
    private String key;
    private KeyInfo keyInfo;
}
