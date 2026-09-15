package com.llama.hub.model;

import lombok.Data;

/** Key 明文副本找回结果 */
@Data
public class RevealResult {
    private String key;

    public RevealResult() {
    }

    public RevealResult(String key) {
        this.key = key;
    }
}
