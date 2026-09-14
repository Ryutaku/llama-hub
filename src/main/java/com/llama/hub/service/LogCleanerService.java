package com.llama.hub.service;

import lombok.extern.slf4j.Slf4j;
import com.llama.hub.mapper.AuditLogMapper;
import com.llama.hub.mapper.CallLogMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@Slf4j
public class LogCleanerService {


    private static final long AUDIT_RETENTION_DAYS = 180;

    private final CallLogMapper callLogMapper;
    private final AuditLogMapper auditLogMapper;

    @Value("${gateway.log.retention-days:90}")
    private int retentionDays;

    public LogCleanerService(CallLogMapper callLogMapper, AuditLogMapper auditLogMapper) {
        this.callLogMapper = callLogMapper;
        this.auditLogMapper = auditLogMapper;
    }

    @Scheduled(cron = "0 0 3 * * *")
    public void clean() {
        try {
            int callLogs = callLogMapper.deleteOlderThan(LocalDateTime.now().minusDays(retentionDays));
            int auditLogs = auditLogMapper.deleteOlderThan(LocalDateTime.now().minusDays(AUDIT_RETENTION_DAYS));
            log.info("Log cleanup: {} call_logs, {} audit_logs removed", callLogs, auditLogs);
        } catch (Exception e) {
            log.error("Log cleanup failed", e);
        }
    }
}
