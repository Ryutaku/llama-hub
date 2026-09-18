package com.llama.hub.controller;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.llama.hub.mapper.AuditLogMapper;
import com.llama.hub.mapper.CallLogMapper;
import com.llama.hub.model.ApiKey;
import com.llama.hub.model.AuditLog;
import com.llama.hub.model.CallLog;
import com.llama.hub.model.DailyStatsInfo;
import com.llama.hub.model.DashboardInfo;
import com.llama.hub.model.KeyCreateResult;
import com.llama.hub.model.KeyInfo;
import com.llama.hub.model.KeyUpdateCommand;
import com.llama.hub.model.PageResult;
import com.llama.hub.model.RevealResult;
import com.llama.hub.model.UpstreamStatusInfo;
import com.llama.hub.model.UsageStatsInfo;
import com.llama.hub.service.AuditService;
import com.llama.hub.service.KeyService;
import com.llama.hub.service.StatsService;
import com.llama.hub.service.UpstreamHealthService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
public class AdminController {

    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int EXPORT_LIMIT = 10000;

    private final KeyService keyService;
    private final StatsService statsService;
    private final UpstreamHealthService healthService;
    private final AuditService auditService;
    private final CallLogMapper callLogMapper;
    private final AuditLogMapper auditLogMapper;

    public AdminController(KeyService keyService, StatsService statsService,
                           UpstreamHealthService healthService, AuditService auditService,
                           CallLogMapper callLogMapper, AuditLogMapper auditLogMapper) {
        this.keyService = keyService;
        this.statsService = statsService;
        this.healthService = healthService;
        this.auditService = auditService;
        this.callLogMapper = callLogMapper;
        this.auditLogMapper = auditLogMapper;
    }

    // ---------- dashboard ----------

    @GetMapping("/api/admin/dashboard")
    public DashboardInfo dashboard() {
        return statsService.dashboard();
    }

    @GetMapping("/api/admin/upstream/status")
    public UpstreamStatusInfo upstreamStatus() {
        return healthService.status();
    }

    @GetMapping("/api/admin/stats/usage")
    public UsageStatsInfo usageStats(@RequestParam(required = false) Long keyId,
                                     @RequestParam(required = false) String startAt,
                                     @RequestParam(required = false) String endAt) {
        return statsService.usageStats(keyId, parseStart(startAt), parseEnd(endAt));
    }

    @GetMapping("/api/admin/stats/daily")
    public DailyStatsInfo dailyStats(@RequestParam(required = false) Long keyId,
                                     @RequestParam(required = false) String startAt,
                                     @RequestParam(required = false) String endAt) {
        return statsService.dailyStats(keyId, parseStart(startAt), parseEnd(endAt));
    }

    // ---------- api keys ----------

    @GetMapping("/api/admin/keys")
    public List<KeyInfo> listKeys() {
        return keyService.list();
    }

    @PostMapping("/api/admin/keys")
    public KeyCreateResult createKey(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String name = (String) body.get("name");
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("名称不能为空");
        }
        Integer number = toInt(body.get("number"));
        String unit = (String) body.get("unit");
        if (unit == null || unit.isEmpty()) {
            throw new IllegalArgumentException("有效期不能为空");
        }
        if (!"permanent".equalsIgnoreCase(unit) && (number == null || number < 1)) {
            throw new IllegalArgumentException("非永久有效期必须提供数量");
        }
        KeyCreateResult created = keyService.create(name.trim(), number == null ? 0 : number, unit,
                toLong(body.get("tokenQuota")), toLong(body.get("requestQuota")));
        auditService.record(username(request), "KEY_CREATE", name,
                "expires: " + number + " " + unit, request.getRemoteAddr());
        return created;
    }

    @PutMapping("/api/admin/keys/{id}")
    public KeyInfo updateKey(@PathVariable Long id, @RequestBody Map<String, Object> body,
                             HttpServletRequest request) {
        KeyUpdateCommand cmd = new KeyUpdateCommand();
        cmd.setId(id);
        List<String> changes = new ArrayList<>();
        if (body.containsKey("name")) {
            String name = (String) body.get("name");
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("名称不能为空");
            }
            cmd.setName(name.trim());
            cmd.setNameChanged(true);
            changes.add("name=" + name.trim());
        }
        if (body.containsKey("unit")) {
            String unit = (String) body.get("unit");
            Integer number = toInt(body.get("number"));
            if (unit == null || unit.isEmpty()) {
                throw new IllegalArgumentException("有效期单位不能为空");
            }
            if (!"permanent".equalsIgnoreCase(unit) && (number == null || number < 1)) {
                throw new IllegalArgumentException("非永久有效期必须提供数量");
            }
            cmd.setExpiresAt(KeyService.expireFrom(number == null ? 0 : number, unit, LocalDateTime.now()));
            cmd.setExpiryChanged(true);
            changes.add("expiresAt=" + ("permanent".equalsIgnoreCase(unit) ? "permanent" : number + " " + unit));
        }
        if (body.containsKey("isActive")) {
            boolean active = Boolean.TRUE.equals(toBool(body.get("isActive")));
            cmd.setActive(active);
            cmd.setActiveChanged(true);
            changes.add("isActive=" + active);
        }
        // 显式传 null 表示「不限」，字段缺失才表示「不修改」
        if (body.containsKey("tokenQuota")) {
            Long quota = toLong(body.get("tokenQuota"));
            cmd.setTokenQuota(quota);
            cmd.setTokenQuotaChanged(true);
            changes.add("tokenQuota=" + (quota == null ? "unlimited" : quota));
        }
        if (body.containsKey("requestQuota")) {
            Long quota = toLong(body.get("requestQuota"));
            cmd.setRequestQuota(quota);
            cmd.setRequestQuotaChanged(true);
            changes.add("requestQuota=" + (quota == null ? "unlimited" : quota));
        }
        if (!cmd.hasChanges()) {
            throw new IllegalArgumentException("没有需要更新的字段");
        }
        KeyInfo updated = keyService.update(cmd);
        auditService.record(username(request), "KEY_UPDATE", updated.getName(),
                "id: " + id + "; " + String.join("; ", changes), request.getRemoteAddr());
        return updated;
    }

    @DeleteMapping("/api/admin/keys/{id}")
    public ResponseEntity<Void> deleteKey(@PathVariable Long id, HttpServletRequest request) {
        ApiKey key = keyService.findById(id);
        keyService.delete(id);
        auditService.record(username(request), "KEY_DELETE",
                key == null ? "id:" + id : key.getName(), "id: " + id, request.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/admin/keys/{id}/reveal")
    public RevealResult revealKey(@PathVariable Long id, HttpServletRequest request) {
        String plain = keyService.reveal(id);
        if (plain == null) {
            throw new IllegalArgumentException("该 Key 未保存明文副本，无法找回（旧密钥可补录明文或删除重建）");
        }
        auditService.record(username(request), "KEY_REVEAL",
                String.valueOf(id), "key revealed", request.getRemoteAddr());
        return new RevealResult(plain);
    }

    @PostMapping("/api/admin/keys/recover-plain")
    public KeyInfo recoverPlain(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        KeyInfo info = keyService.backfillPlain((String) body.get("plain"));
        auditService.record(username(request), "KEY_RECOVER", info.getName(),
                "id: " + info.getId() + " plain backfilled", request.getRemoteAddr());
        return info;
    }

    // ---------- call logs ----------

    @GetMapping("/api/admin/logs")
    public PageResult<CallLog> logs(@RequestParam(required = false) Long keyId,
                                    @RequestParam(required = false) String startAt,
                                    @RequestParam(required = false) String endAt,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "50") int size) {
        int pageNum = Math.max(1, page);
        int pageSize = Math.min(100, Math.max(1, size));
        PageHelper.startPage(pageNum, pageSize);
        List<CallLog> rows = callLogMapper.selectList(keyId, parseStart(startAt), parseEnd(endAt));
        return new PageResult<>(rows, new PageInfo<>(rows).getTotal(), page, pageSize);
    }

    @GetMapping("/api/admin/logs/export")
    public ResponseEntity<byte[]> exportLogs(@RequestParam(required = false) Long keyId,
                                             @RequestParam(required = false) String startAt,
                                             @RequestParam(required = false) String endAt) {
        long count = callLogMapper.count(keyId, parseStart(startAt), parseEnd(endAt));
        if (count > EXPORT_LIMIT) {
            return ResponseEntity.badRequest().body(utf8Bom("记录超过 " + EXPORT_LIMIT + " 条，请缩小筛选范围"));
        }
        List<CallLog> logs = callLogMapper.selectList(keyId, parseStart(startAt), parseEnd(endAt));

        StringBuilder sb = new StringBuilder();
        sb.append("时间,Key名称,端点,模型,输入tokens,输出tokens,总tokens,缓存tokens,缓存命中率,生成速度(tokens/s),耗时(ms),状态码,错误信息\n");
        for (CallLog l : logs) {
            sb.append(csv(l.getStartedAt() == null ? "" : CSV_TIME.format(l.getStartedAt())))
                    .append(',').append(csv(l.getKeyName()))
                    .append(',').append(csv(l.getEndpoint()))
                    .append(',').append(csv(l.getModel()))
                    .append(',').append(csv(l.getPromptTokens()))
                    .append(',').append(csv(l.getCompletionTokens()))
                    .append(',').append(csv(l.getTotalTokens()))
                    .append(',').append(csv(l.getCachedTokens()))
                    .append(',').append(csv(l.getCacheHitRate()))
                    .append(',').append(csv(l.getTokensPerSec()))
                    .append(',').append(csv(l.getDurationMs()))
                    .append(',').append(csv(l.getStatusCode()))
                    .append(',').append(csv(l.getErrorMsg()))
                    .append('\n');
        }
        String filename = "call_logs_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".csv";
        byte[] body = utf8Bom(sb.toString());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8));
        headers.setContentDispositionFormData("attachment", filename);
        return ResponseEntity.ok().headers(headers).body(body);
    }

    // ---------- audit logs ----------

    @GetMapping("/api/admin/audit-logs")
    public PageResult<AuditLog> auditLogs(@RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "50") int size,
                                          @RequestParam(required = false) String username,
                                          @RequestParam(required = false) String action,
                                          @RequestParam(required = false) String startAt,
                                          @RequestParam(required = false) String endAt) {
        int pageNum = Math.max(1, page);
        int pageSize = Math.min(100, Math.max(1, size));
        PageHelper.startPage(pageNum, pageSize);
        List<AuditLog> rows = auditLogMapper.selectList(username, action, parseStart(startAt), parseEnd(endAt));
        return new PageResult<>(rows, new PageInfo<>(rows).getTotal(), page, pageSize);
    }

    // ---------- helpers ----------

    /** 开始时间：兼容 "yyyy-MM-dd"（当日 00:00）与 ISO datetime */
    private LocalDateTime parseStart(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        s = s.trim();
        try {
            return LocalDateTime.parse(s);
        } catch (Exception ignored) { }
        try {
            return java.time.LocalDate.parse(s).atStartOfDay();
        } catch (Exception e) {
            return null;
        }
    }

    /** 结束时间：兼容 "yyyy-MM-dd"（次日 00:00，含当天）与 ISO datetime */
    private LocalDateTime parseEnd(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        s = s.trim();
        try {
            return LocalDateTime.parse(s);
        } catch (Exception ignored) { }
        try {
            return java.time.LocalDate.parse(s).plusDays(1).atStartOfDay();
        } catch (Exception e) {
            return null;
        }
    }

    private String username(HttpServletRequest request) {
        Object u = request.getSession().getAttribute(AuthController.SESSION_USER);
        return u instanceof String ? (String) u : "unknown";
    }

    private Integer toInt(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long toLong(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Boolean toBool(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Boolean) {
            return (Boolean) o;
        }
        return "true".equalsIgnoreCase(String.valueOf(o));
    }

    private String csv(Object o) {
        if (o == null) {
            return "";
        }
        String s = String.valueOf(o);
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private byte[] utf8Bom(String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[bytes.length + 3];
        out[0] = (byte) 0xEF;
        out[1] = (byte) 0xBB;
        out[2] = (byte) 0xBF;
        System.arraycopy(bytes, 0, out, 3, bytes.length);
        return out;
    }
}
