package com.llama.hub.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private String upstream = "http://127.0.0.1:18082";

    private Log log = new Log();

    private Admin admin = new Admin();

    @Getter
    @Setter
    public static class Log {
        private int retentionDays = 90;
        private boolean bodyEnabled = false;
    }

    @Getter
    @Setter
    public static class Admin {
        private String allowedIps = "";
    }
}
