package com.gateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class UpstreamHealthService {

    private static final Logger log = LoggerFactory.getLogger(UpstreamHealthService.class);

    private final WebClient.Builder webClientBuilder;

    @Value("${gateway.upstream:http://127.0.0.1:18082}")
    private String upstream;

    private volatile boolean up = false;
    private volatile long latencyMs = -1;
    private volatile long lastCheckAt = 0L;

    public UpstreamHealthService(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    @PostConstruct
    public void init() {
        probe();
    }

    @Scheduled(fixedRate = 30000)
    public void probe() {
        long start = System.currentTimeMillis();
        try {
            WebClient client = webClientBuilder.clone()
                    .baseUrl(upstream)
                    .build();
            String body = client.get().uri("/health").retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(2))
                    .block();
            boolean ok = body != null && (body.contains("ok") || body.contains("\"status\""));
            up = ok;
            latencyMs = System.currentTimeMillis() - start;
            lastCheckAt = System.currentTimeMillis();
            if (!ok) {
                log.warn("Upstream health check returned unexpected body: {}", body);
            }
        } catch (Exception e) {
            up = false;
            latencyMs = -1;
            lastCheckAt = System.currentTimeMillis();
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("up", up);
        m.put("latencyMs", latencyMs);
        m.put("lastCheckedAt", lastCheckAt == 0 ? null : lastCheckAt);
        return m;
    }

    public boolean isUp() {
        return up;
    }
}
