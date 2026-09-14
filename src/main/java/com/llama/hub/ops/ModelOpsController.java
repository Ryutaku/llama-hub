package com.llama.hub.ops;

import com.llama.hub.controller.AuthController;
import com.llama.hub.model.ConfigSaveResult;
import com.llama.hub.model.DiffItem;
import com.llama.hub.model.ModelConfigInfo;
import com.llama.hub.model.ModelPresetInfo;
import com.llama.hub.model.ModelSnapshotResult;
import com.llama.hub.model.ModelStateResult;
import com.llama.hub.model.ModelStatusInfo;
import com.llama.hub.service.AuditService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletRequest;
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
    private final ModelPresetService presetService;
    private final ModelLogStreamService logStreamService;
    private final AuditService auditService;

    public ModelOpsController(ModelStatusService statusService, ModelOpsService opsService,
                              ModelConfigService configService, ModelPresetService presetService,
                              ModelLogStreamService logStreamService,
                              AuditService auditService) {
        this.statusService = statusService;
        this.opsService = opsService;
        this.configService = configService;
        this.presetService = presetService;
        this.logStreamService = logStreamService;
        this.auditService = auditService;
    }

    @GetMapping("/api/admin/model/status")
    public ModelStatusInfo status() {
        return statusService.status();
    }

    @PostMapping("/api/admin/model/start")
    public ResponseEntity<ModelStateResult> start(HttpServletRequest request) {
        opsService.start(username(request), ip(request));
        ModelStateResult m = new ModelStateResult();
        m.setState(ModelStatusService.State.STARTING.name());
        return ResponseEntity.accepted().body(m);
    }

    @PostMapping("/api/admin/model/stop")
    public ResponseEntity<ModelStateResult> stop(HttpServletRequest request) {
        opsService.stop(username(request), ip(request));
        ModelStateResult m = new ModelStateResult();
        m.setState(ModelStatusService.State.STOPPING.name());
        return ResponseEntity.accepted().body(m);
    }

    @GetMapping("/api/admin/model/config")
    public ModelConfigInfo config() {
        return configService.get();
    }

    @PutMapping("/api/admin/model/config")
    public ConfigSaveResult saveConfig(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String args = body.get("args") == null ? null : String.valueOf(body.get("args"));
        String env = body.get("env") == null ? "" : String.valueOf(body.get("env"));
        ConfigSaveResult result = configService.save(args, env, "edited");
        auditService.record(username(request), "MODEL_CONFIG_CHANGE", "llama-server",
                "diff=" + result.getDiff(), ip(request));
        return result;
    }

    @PostMapping("/api/admin/model/config/snapshot")
    public ModelSnapshotResult snapshot(HttpServletRequest request) {
        ModelSnapshotResult result = configService.snapshot();
        auditService.record(username(request), "MODEL_SNAPSHOT", "llama-server",
                "diff=" + result.getDiff(), ip(request));
        return result;
    }

    @GetMapping("/api/admin/model/presets")
    public List<ModelPresetInfo> presets() {
        return presetService.list();
    }

    @PostMapping("/api/admin/model/presets")
    public ModelPresetInfo savePreset(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String name = body.get("name") == null ? null : String.valueOf(body.get("name"));
        String note = body.get("note") == null ? "" : String.valueOf(body.get("note"));
        String args = body.get("args") == null ? null : String.valueOf(body.get("args"));
        String env = body.get("env") == null ? null : String.valueOf(body.get("env"));
        ModelPresetInfo result = presetService.save(name, note, args, env, "current");
        auditService.record(username(request), "MODEL_PRESET_SAVE", "llama-server",
                "name=" + result.getName(), ip(request));
        return result;
    }

    @PutMapping("/api/admin/model/presets/{id}")
    public ModelPresetInfo updatePreset(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                        HttpServletRequest request) {
        String name = body.get("name") == null ? null : String.valueOf(body.get("name"));
        String note = body.get("note") == null ? null : String.valueOf(body.get("note"));
        ModelPresetInfo result = presetService.update(id, name, note);
        auditService.record(username(request), "MODEL_PRESET_UPDATE", "llama-server",
                "id=" + id + " name=" + result.getName(), ip(request));
        return result;
    }

    @DeleteMapping("/api/admin/model/presets/{id}")
    public ResponseEntity<Void> deletePreset(@PathVariable Long id, HttpServletRequest request) {
        presetService.delete(id);
        auditService.record(username(request), "MODEL_PRESET_DELETE", "llama-server", "id=" + id, ip(request));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/admin/model/presets/{id}/diff")
    public List<DiffItem> presetDiff(@PathVariable Long id) {
        return presetService.diffWithCurrent(id);
    }

    @PostMapping("/api/admin/model/presets/{id}/apply")
    public ConfigSaveResult applyPreset(@PathVariable Long id, HttpServletRequest request) {
        ConfigSaveResult result = presetService.apply(id);
        auditService.record(username(request), "MODEL_PRESET_APPLY", "llama-server",
                "id=" + id + " diff=" + result.getDiff(), ip(request));
        return result;
    }

    @PostMapping("/api/admin/model/presets/{id}/start")
    public ResponseEntity<ConfigSaveResult> startWithPreset(@PathVariable Long id, HttpServletRequest request) {
        ConfigSaveResult result = presetService.startWith(id, username(request), ip(request));
        auditService.record(username(request), "MODEL_PRESET_START", "llama-server",
                "id=" + id + " diff=" + result.getDiff(), ip(request));
        return ResponseEntity.accepted().body(result);
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
