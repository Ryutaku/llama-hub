package com.llama.hub.model;

import lombok.Data;

/** 登录结果 */
@Data
public class LoginResult {
    private boolean ok;
    private String username;
    private String error;
    private Boolean locked;

    public static LoginResult success(String username) {
        LoginResult r = new LoginResult();
        r.setOk(true);
        r.setUsername(username);
        return r;
    }

    public static LoginResult failure(String error) {
        LoginResult r = new LoginResult();
        r.setOk(false);
        r.setError(error);
        return r;
    }

    public static LoginResult locked() {
        LoginResult r = failure("连续失败次数过多，请5分钟后再试");
        r.setLocked(true);
        return r;
    }
}
