package com.llama.hub.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "gateway.model")
public class OpsProperties {

    private String sshHost = "192.168.2.185";
    private int sshPort = 22;
    private String sshUser = "root";
    private int sshTimeoutSeconds = 15;
    private int apiPort = 18082;
    private int cudaDevice = 0;
    private String baseDir = "/home/llama-cpp";
    private String serverBinary = "/home/llama-cpp/llama.cpp/build-cuda/bin/llama-server";
    private String modelPath = "";
    private String mmprojPath = "";
    private long probeIntervalMs = 5000;
    private int startTimeoutSeconds = 300;
    private int stopTimeoutSeconds = 180;
    private int logTailLines = 500;

    public String logFile() {
        return baseDir + "/logs/llama.log";
    }

    public String pidFile() {
        return baseDir + "/logs/llama.pid";
    }

    public String startScript() {
        return baseDir + "/start-gateway.sh";
    }

    public String stopScript() {
        return baseDir + "/stop.sh";
    }
}
