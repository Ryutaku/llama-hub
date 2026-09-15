package com.llama.hub.model;

import lombok.Data;

/** 当前会话用户信息 */
@Data
public class MeInfo {
    private boolean ok;
    private String username;

    public MeInfo() {
    }

    public MeInfo(boolean ok, String username) {
        this.ok = ok;
        this.username = username;
    }
}
