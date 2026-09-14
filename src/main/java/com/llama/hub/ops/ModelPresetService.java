package com.llama.hub.ops;

import com.llama.hub.mapper.ModelPresetMapper;
import com.llama.hub.model.ConfigSaveResult;
import com.llama.hub.model.DiffItem;
import com.llama.hub.model.ModelPreset;
import com.llama.hub.model.ModelPresetInfo;
import com.llama.hub.service.ApiException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 参数版本（preset）：把满意的完整参数行 + 环境变量行存成命名版本，
 * 可应用为当前配置（model_config），或应用后直接启动模型。
 * model_config 仍是启动唯一来源，版本应用即覆盖它。
 */
@Service
public class ModelPresetService {

    private final ModelPresetMapper mapper;
    private final ModelConfigService configService;
    private final ModelOpsService opsService;
    private final ModelStatusService statusService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int MAX_NAME_LEN = 64;
    private static final int MAX_NOTE_LEN = 500;

    public ModelPresetService(ModelPresetMapper mapper, ModelConfigService configService,
                              ModelOpsService opsService, ModelStatusService statusService) {
        this.mapper = mapper;
        this.configService = configService;
        this.opsService = opsService;
        this.statusService = statusService;
    }

    /** 版本列表，附 isCurrent（与库内当前配置一致）/ isRunning（与运行进程一致）标记。 */
    public List<ModelPresetInfo> list() {
        String curArgs = configService.currentArgs();
        String curEnv = configService.currentEnv();
        String cmdline = statusService.lastCmdline();
        String runningArgs = (cmdline == null || cmdline.isBlank()) ? "" : ModelConfigService.extractFullArgs(cmdline);
        Map<String, String> runningEnvMap = ModelConfigService.parseEnvMap(
                ModelConfigService.envLine(statusService.lastEnviron()));
        List<ModelPresetInfo> out = new ArrayList<>();
        for (ModelPreset p : mapper.findAll()) {
            String args = ModelConfigService.argsFromJson(p.getConfigJson());
            String env = ModelConfigService.envFromJson(p.getConfigJson());
            ModelPresetInfo m = new ModelPresetInfo();
            m.setId(p.getId());
            m.setName(p.getName());
            m.setNote(p.getNote());
            m.setSource(p.getSource());
            m.setCreatedAt(p.getCreatedAt() == null ? null : p.getCreatedAt().toString());
            m.setArgs(args);
            m.setEnv(env);
            m.setIsCurrent(ModelConfigService.sameTokens(args, curArgs)
                    && ModelConfigService.envMapsEqual(ModelConfigService.parseEnvMap(env),
                    ModelConfigService.parseEnvMap(curEnv)));
            m.setIsRunning(!runningArgs.isEmpty()
                    && ModelConfigService.sameTokens(args, runningArgs)
                    && ModelConfigService.envMapsEqual(ModelConfigService.parseEnvMap(env), runningEnvMap));
            out.add(m);
        }
        return out;
    }

    /** 存版本：args/env 缺省时取当前配置；词法校验 + 名称唯一。 */
    public ModelPresetInfo save(String name, String note, String args, String env, String source) {
        if (name == null || name.trim().isEmpty()) {
            throw new ApiException(400, "版本名不能为空");
        }
        name = name.trim();
        if (name.length() > MAX_NAME_LEN) {
            throw new ApiException(400, "版本名过长（上限 " + MAX_NAME_LEN + " 字符）");
        }
        if (note != null && note.trim().length() > MAX_NOTE_LEN) {
            throw new ApiException(400, "备注过长（上限 " + MAX_NOTE_LEN + " 字符）");
        }
        if (mapper.findByName(name) != null) {
            throw new ApiException(409, "版本名已存在: " + name);
        }
        if (args == null || args.trim().isEmpty()) {
            args = configService.currentArgs();
        }
        if (env == null) {
            env = "";
        }
        ModelConfigService.validateArgs(args);
        ModelConfigService.validateEnv(env);
        ModelPreset p = new ModelPreset();
        p.setName(name);
        p.setNote(note == null ? "" : note.trim());
        p.setConfigJson(toJson(Map.of("args", args.trim().replaceAll("\\s+", " "), "env", env.trim().replaceAll("\\s+", " "))));
        p.setSource(source);
        p.setCreatedAt(LocalDateTime.now());
        mapper.insertRow(p);
        ModelPresetInfo m = new ModelPresetInfo();
        m.setId(p.getId());
        m.setName(name);
        m.setNote(p.getNote());
        m.setSource(source);
        m.setCreatedAt(p.getCreatedAt().toString());
        return m;
    }

    /** 版本与当前配置的 diff（预览用，不落库）。 */
    public List<DiffItem> diffWithCurrent(Long id) {
        ModelPreset p = require(id);
        return configService.diffWith(
                ModelConfigService.argsFromJson(p.getConfigJson()),
                ModelConfigService.envFromJson(p.getConfigJson()));
    }

    /** 应用版本为当前配置（覆盖 model_config，重启生效）。 */
    public ConfigSaveResult apply(Long id) {
        ModelPreset p = require(id);
        return configService.save(
                ModelConfigService.argsFromJson(p.getConfigJson()),
                ModelConfigService.envFromJson(p.getConfigJson()),
                "preset");
    }

    /** 应用版本 + 启动模型（模型运行中/启动中时由 opsService 拒绝）。 */
    public ConfigSaveResult startWith(Long id, String username, String ip) {
        ConfigSaveResult result = apply(id);
        opsService.start(username, ip);
        result.setState(ModelStatusService.State.STARTING.name());
        return result;
    }

    public ModelPresetInfo update(Long id, String name, String note) {
        ModelPreset p = require(id);
        if (name == null || name.trim().isEmpty()) {
            throw new ApiException(400, "版本名不能为空");
        }
        name = name.trim();
        if (name.length() > MAX_NAME_LEN) {
            throw new ApiException(400, "版本名过长（上限 " + MAX_NAME_LEN + " 字符）");
        }
        if (note != null && note.trim().length() > MAX_NOTE_LEN) {
            throw new ApiException(400, "备注过长（上限 " + MAX_NOTE_LEN + " 字符）");
        }
        ModelPreset same = mapper.findByName(name);
        if (same != null && !same.getId().equals(id)) {
            throw new ApiException(409, "版本名已存在: " + name);
        }
        p.setName(name);
        p.setNote(note == null ? "" : note.trim());
        mapper.updateRow(p);
        ModelPresetInfo m = new ModelPresetInfo();
        m.setId(p.getId());
        m.setName(p.getName());
        m.setNote(p.getNote());
        m.setSource(p.getSource());
        return m;
    }

    public void delete(Long id) {
        if (mapper.deleteRow(id) == 0) {
            throw new ApiException(404, "版本不存在: " + id);
        }
    }

    private ModelPreset require(Long id) {
        ModelPreset p = mapper.findById(id);
        if (p == null) {
            throw new ApiException(404, "版本不存在: " + id);
        }
        return p;
    }

    private String toJson(Map<String, Object> params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            throw new ApiException(500, "版本序列化失败");
        }
    }
}
