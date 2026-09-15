package com.llama.hub.model;

import lombok.Data;

/** 上游 llama-server 健康状态（最近一次探测的缓存值） */
@Data
public class UpstreamStatusInfo {
    private boolean up;
    private long latencyMs;
    private Long lastCheckedAt;
}
