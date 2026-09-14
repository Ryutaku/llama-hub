package com.llama.hub.ops;

import com.llama.hub.controller.AuthController;
import com.llama.hub.service.AuditService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型运维 API（管理端，受 AdminAuthInterceptor 会话 + IP 白名单保护）。
 */
@RestController
public class ModelOpsController {

    private final ModelStatusService statusService;
    private final ModelOpsService opsService;
    private final ModelConfigService configService;
    private final ModelLogStreamService logStreamService;
    private final AuditService auditService;

    public ModelOpsController(ModelStatusService statusService, ModelOpsService opsService,
                              ModelConfigService configService, ModelLogStreamService logStreamService,
                              AuditService auditService) {
        this.statusService = statusService;
        this.opsService = opsService;
        this.configService = configService;
        this.logStreamService = logStreamService;
        this.auditService = auditService;
    }

    @GetMapping("/api/admin/model/status")
    public Map<String, Object> status() {
        return statusService.status();
    }

    @PostMapping("/api/admin/model/start")
    public ResponseEntity<Map<String, Object>> start(HttpServletRequest request) {
        opsService.start(username(request), ip(request));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", ModelStatusService.State.STARTING.name());
        return ResponseEntity.accepted().body(m);
    }

    @PostMapping("/api/admin/model/stop")
    public ResponseEntity<Map<String, Object>> stop(HttpServletRequest request) {
        opsService.stop(username(request), ip(request));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", ModelStatusService.State.STOPPING.name());
        return ResponseEntity.accepted().body(m);
    }

    @GetMapping("/api/admin/model/config")
    public Map<String, Object> config() {
        return configService.get();
    }

    @PutMapping("/api/admin/model/config")
    public Map<String, Object> saveConfig(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String args = body.get("args") == null ? null : String.valueOf(body.get("args"));
        Map<String, Object> result = configService.save(args, "edited");
        auditService.record(username(request), "MODEL_CONFIG_CHANGE", "llama-server",
                "diff=" + result.get("diff"), ip(request));
        return result;
    }

    @PostMapping("/api/admin/model/config/snapshot")
    public Map<String, Object> snapshot(HttpServletRequest request) {
        Map<String, Object> result = configService.snapshot();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diff = (List<Map<String, Object>>) result.get("diff");
        auditService.record(username(request), "MODEL_SNAPSHOT", "llama-server",
                "diff=" + diff, ip(request));
        return result;
    }

    @GetMapping(value = "/api/admin/model/logs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter logs() {
        return logStreamService.subscribe();
    }

    @GetMapping(value = "/api/admin/model/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        return statusService.subscribeEvents();
    }

    private String username(HttpServletRequest request) {
        if (request.getSession(false) == null) {
            return "unknown";
        }
        Object u = request.getSession(false).getAttribute(AuthController.SESSION_USER);
        return u == null ? "unknown" : String.valueOf(u);
    }

    private String ip(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
