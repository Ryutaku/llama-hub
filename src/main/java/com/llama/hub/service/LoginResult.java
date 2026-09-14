package com.llama.hub.service;

public class LoginResult {

    private final boolean success;
    private final String username;
    private final String message;

    private LoginResult(boolean success, String username, String message) {
        this.success = success;
        this.username = username;
        this.message = message;
    }

    public static LoginResult ok(String username) {
        return new LoginResult(true, username, null);
    }

    public static LoginResult fail(String message) {
        return new LoginResult(false, null, message);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getUsername() {
        return username;
    }

    public String getMessage() {
        return message;
    }
}
