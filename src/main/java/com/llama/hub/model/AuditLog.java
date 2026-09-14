package com.llama.hub.model;

import lombok.Data;
import java.time.LocalDateTime;


@Data
public class AuditLog {
    private Long id;
    private String username;
    private String action;
    private String target;
    private String detail;
    private String ip;
    private LocalDateTime createdAt;
}
