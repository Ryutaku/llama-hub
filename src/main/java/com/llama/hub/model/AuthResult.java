package com.llama.hub.model;

import lombok.Data;

/** 登出 / 修改密码等认证类操作结果 */
@Data
public class AuthResult {
    private boolean ok;
    private String error;

    public static AuthResult ok() {
        AuthResult r = new AuthResult();
        r.setOk(true);
        return r;
    }

    public static AuthResult failure(String error) {
        AuthResult r = new AuthResult();
        r.setOk(false);
        r.setError(error);
        return r;
    }
}
