package com.llama.hub.controller;

import com.llama.hub.model.HealthInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping("/health")
    public HealthInfo health() {
        return new HealthInfo("ok", "llama-hub");
    }
}
