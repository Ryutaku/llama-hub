package com.llama.hub.model;

import lombok.Data;

/** 网关自身健康检查返回 */
@Data
public class HealthInfo {
    private String status;
    private String service;

    public HealthInfo() {
    }

    public HealthInfo(String status, String service) {
        this.status = status;
        this.service = service;
    }
}
