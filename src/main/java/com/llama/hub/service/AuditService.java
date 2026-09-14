package com.llama.hub.service;

import com.llama.hub.mapper.AuditLogMapper;
import com.llama.hub.model.AuditLog;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class AuditService {

    private final AuditLogMapper auditLogMapper;

    public AuditService(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    public void record(String username, String action, String target, String detail, String ip) {
        AuditLog log = new AuditLog();
        log.setUsername(username == null ? "unknown" : username);
        log.setAction(action);
        log.setTarget(truncate(target, 200));
        log.setDetail(truncate(detail, 500));
        log.setIp(truncate(ip, 45));
        log.setCreatedAt(LocalDateTime.now());
        auditLogMapper.insert(log);
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
