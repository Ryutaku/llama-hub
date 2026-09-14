package com.gateway.controller;

import com.gateway.model.AuditLog;
import com.gateway.model.CallLog;
import com.gateway.repository.AuditLogRepository;
import com.gateway.repository.CallLogRepository;
import com.gateway.service.AuditService;
import com.gateway.service.KeyService;
import com.gateway.service.StatsService;
import com.gateway.service.UpstreamHealthService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
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
    private final CallLogRepository callLogRepository;
    private final AuditLogRepository auditLogRepository;

    public AdminController(KeyService keyService, StatsService statsService,
                           UpstreamHealthService healthService, AuditService auditService,
                           CallLogRepository callLogRepository, AuditLogRepository auditLogRepository) {
        this.keyService = keyService;
        this.statsService = statsService;
        this.healthService = healthService;
        this.auditService = auditService;
        this.callLogRepository = callLogRepository;
        this.auditLogRepository = auditLogRepository;
    }

    // ---------- dashboard ----------

    @GetMapping("/api/admin/dashboard")
    public Map<String, Object> dashboard() {
        return statsService.dashboard();
    }

    @GetMapping("/api/admin/upstream/status")
    public Map<String, Object> upstreamStatus() {
        return healthService.status();
    }

    @GetMapping("/api/admin/stats/usage")
    public Map<String, Object> usageStats(@RequestParam(required = false) Long keyId,
                                          @RequestParam(required = false) String startAt,
                                          @RequestParam(required = false) String endAt) {
        return statsService.usageStats(keyId, parseStart(startAt), parseEnd(endAt));
    }

    // ---------- api keys ----------

    @GetMapping("/api/admin/keys")
    public List<Map<String, Object>> listKeys() {
        return keyService.list();
    }

    @PostMapping("/api/admin/keys")
    public Map<String, Object> createKey(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String name = (String) body.get("name");
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("名称不能为空");
        }
        Integer number = toInt(body.get("number"));
        String unit = (String) body.get("unit");
        if (number == null || number < 1 || unit == null || unit.isEmpty()) {
            throw new IllegalArgumentException("有效期不能为空");
        }
        Map<String, Object> created = keyService.create(name.trim(), number, unit,
                toLong(body.get("tokenQuota")), toLong(body.get("requestQuota")));
        auditService.record(username(request), "KEY_CREATE", name,
                "expires: " + number + " " + unit, request.getRemoteAddr());
        return created;
    }

    @PutMapping("/api/admin/keys/{id}")
    public Map<String, Object> updateKey(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                         HttpServletRequest request) {
        Integer number = toInt(body.get("number"));
        String unit = (String) body.get("unit");
        Boolean isActive = toBool(body.get("isActive"));
        Long tokenQuota = toLong(body.get("tokenQuota"));
        Long requestQuota = toLong(body.get("requestQuota"));
        boolean hasExpiry = number != null && unit != null;
        boolean hasQuota = body.containsKey("tokenQuota") || body.containsKey("requestQuota");
        if ((!hasExpiry) && isActive == null && !hasQuota) {
            throw new IllegalArgumentException("没有需要更新的字段");
        }
        Long tokenQuotaArg = hasQuota ? tokenQuota : null;
        Long requestQuotaArg = hasQuota ? requestQuota : null;
        Map<String, Object> updated = keyService.update(id,
                hasExpiry ? number : null, hasExpiry ? unit : null, isActive,
                hasQuota ? tokenQuotaArg : null, hasQuota ? requestQuotaArg : null);
        auditService.record(username(request), "KEY_UPDATE", (String) updated.get("name"),
                "id: " + id, request.getRemoteAddr());
        return updated;
    }

    @DeleteMapping("/api/admin/keys/{id}")
    public Map<String, Object> deleteKey(@PathVariable Long id, HttpServletRequest request) {
        Map<String, Object> key = keyService.list().stream()
                .filter(m -> m.get("id") != null && id.equals(m.get("id")))
                .findFirst().orElse(null);
        keyService.delete(id);
        auditService.record(username(request), "KEY_DELETE",
                key == null ? "id:" + id : (String) key.get("name"), "id: " + id, request.getRemoteAddr());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }

    @PostMapping("/api/admin/keys/{id}/reveal")
    public Map<String, Object> revealKey(@PathVariable Long id, HttpServletRequest request) {
        String plain = keyService.reveal(id);
        if (plain == null) {
            throw new IllegalArgumentException("该 Key 未保存明文副本，无法找回（旧密钥可删除重建）");
        }
        auditService.record(username(request), "KEY_REVEAL",
                String.valueOf(id), "key revealed", request.getRemoteAddr());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", plain);
        return m;
    }

    // ---------- call logs ----------

    @GetMapping("/api/admin/logs")
    public Map<String, Object> logs(@RequestParam(required = false) Long keyId,
                                    @RequestParam(required = false) String startAt,
                                    @RequestParam(required = false) String endAt,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "50") int size) {
        Page<CallLog> result = callLogRepository.findAll(buildLogSpec(keyId, startAt, endAt),
                PageRequest.of(Math.max(0, page - 1), Math.min(100, Math.max(1, size)),
                        Sort.by(Sort.Direction.DESC, "startedAt")));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("content", result.getContent());
        m.put("total", result.getTotalElements());
        m.put("page", page);
        m.put("size", result.getSize());
        return m;
    }

    @GetMapping("/api/admin/logs/export")
    public ResponseEntity<byte[]> exportLogs(@RequestParam(required = false) Long keyId,
                                             @RequestParam(required = false) String startAt,
                                             @RequestParam(required = false) String endAt) {
        Specification<CallLog> spec = buildLogSpec(keyId, startAt, endAt);
        long count = callLogRepository.count(spec);
        if (count > EXPORT_LIMIT) {
            return ResponseEntity.badRequest().body(utf8Bom("记录超过 " + EXPORT_LIMIT + " 条，请缩小筛选范围"));
        }
        List<CallLog> logs = callLogRepository.findAll(spec,
                Sort.by(Sort.Direction.DESC, "startedAt"));

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
    public Map<String, Object> auditLogs(@RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "50") int size,
                                         @RequestParam(required = false) String username,
                                         @RequestParam(required = false) String action,
                                         @RequestParam(required = false) String startAt,
                                         @RequestParam(required = false) String endAt) {
        Specification<AuditLog> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (username != null && !username.isEmpty()) {
                ps.add(cb.equal(root.get("username"), username));
            }
            if (action != null && !action.isEmpty()) {
                ps.add(cb.equal(root.get("action"), action));
            }
            LocalDateTime start = parseStart(startAt);
            LocalDateTime end = parseEnd(endAt);
            if (start != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), start));
            }
            if (end != null) {
                ps.add(cb.lessThan(root.get("createdAt"), end));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        Page<AuditLog> result = auditLogRepository.findAll(spec,
                PageRequest.of(Math.max(0, page - 1), Math.min(100, Math.max(1, size)),
                        Sort.by(Sort.Direction.DESC, "createdAt")));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("content", result.getContent());
        m.put("total", result.getTotalElements());
        m.put("page", page);
        m.put("size", result.getSize());
        return m;
    }

    // ---------- helpers ----------

    private Specification<CallLog> buildLogSpec(Long keyId, String startAt, String endAt) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (keyId != null && keyId > 0) {
                ps.add(cb.equal(root.get("keyId"), keyId));
            }
            LocalDateTime start = parseStart(startAt);
            LocalDateTime end = parseEnd(endAt);
            if (start != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("startedAt"), start));
            }
            if (end != null) {
                ps.add(cb.lessThan(root.get("startedAt"), end));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

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
