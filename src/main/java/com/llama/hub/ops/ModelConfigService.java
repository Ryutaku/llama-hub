package com.llama.hub.ops;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.llama.hub.config.OpsProperties;
import com.llama.hub.mapper.ModelConfigMapper;
import com.llama.hub.model.ModelConfig;
import com.llama.hub.service.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 模型启动参数：唯一来源是 H2 model_config 表（单行，存完整参数行）。
 * 完整参数行 = llama-server 进程的全部参数（不含二进制路径），-m/-mm/--host/--port 等均可自由增删，
 * UI 直接编辑，启动脚本原样渲染进 start-gateway.sh，重启生效；
 * 快照从运行进程 cmdline 提取同一行，防止漂移。
 */
@Service
public class ModelConfigService {

    private static final Logger log = LoggerFactory.getLogger(ModelConfigService.class);

    private final ModelConfigMapper mapper;
    private final ModelStatusService statusService;
    private final OpsProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Pattern FLAG_RE = Pattern.compile("^--[a-z][a-z0-9-]*$|^-[a-zA-Z][a-zA-Z0-9]*$");
    private static final Pattern VALUE_RE = Pattern.compile("^[A-Za-z0-9/][A-Za-z0-9._,+-/]*$");
    private static final int MAX_ARGS_LEN = 4000;

    /** 基线 = 2026-09-14 185 实际运行进程完整参数行（非 start.sh 旧值）。 */
    private final String defaultArgs;

    public ModelConfigService(ModelConfigMapper mapper, ModelStatusService statusService, OpsProperties props) {
        this.mapper = mapper;
        this.statusService = statusService;
        this.props = props;
        this.defaultArgs = String.join(" ",
                "-m", props.getModelPath(),
                "-mm", props.getMmprojPath(),
                "--chat-template-file", props.getBaseDir() + "/chat_template.jinja",
                "-a", "qwen3", "-ngl", "99", "-ts", "36,30", "-sm", "layer", "-c", "400000",
                "-ctk", "q8_0", "-ctv", "q8_0", "-np", "2",
                "--no-kv-unified",
                "--spec-type", "draft-mtp", "--spec-draft-n-max", "6",
                "--spec-draft-p-min", "0.3", "--spec-draft-p-split", "0.1",
                "-fa", "on", "-t", "64", "-tb", "64",
                "--batch-size", "2048", "--ubatch-size", "1024",
                "-cram", "65536", "--image-min-tokens", "1024",
                "--reasoning", "auto", "--reasoning-preserve", "--jinja",
                "--host", "0.0.0.0", "--port", String.valueOf(props.getApiPort()),
                "--no-log-timestamps");
    }

    /** 当前配置：完整参数行 + 来源 + 运行进程参数行（drift 表示两者不一致）。 */
    public Map<String, Object> get() {
        ModelConfig row = ensureRow();
        String args = argsFromJson(row.getConfigJson());
        String cmdline = statusService.lastCmdline();
        String runningArgs = (cmdline == null || cmdline.isBlank()) ? "" : extractFullArgs(cmdline);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("args", args);
        m.put("source", row.getSource());
        m.put("updatedAt", row.getUpdatedAt() == null ? null : row.getUpdatedAt().toString());
        m.put("runningArgs", runningArgs.isEmpty() ? null : runningArgs);
        m.put("drift", !runningArgs.isEmpty() && !sameTokens(runningArgs, args));
        return m;
    }

    /** 保存完整参数行（校验 + diff），返回归一化参数行与 diff。 */
    public Map<String, Object> save(String args, String source) {
        validateArgs(args);
        String normalized = normalize(args);
        String oldArgs = currentArgs();
        List<Map<String, Object>> diff = diffArgs(oldArgs, normalized);
        upsertRow(normalized, source);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("args", normalized);
        m.put("source", source);
        m.put("diff", diff);
        return m;
    }

    /** 从运行进程 cmdline 提取完整参数行，diff 后落库（source=snapshot）。 */
    public Map<String, Object> snapshot() {
        String cmdline = statusService.lastCmdline();
        if (cmdline == null || cmdline.isBlank()) {
            throw new ApiException(409, "模型未运行，无法从服务器快照参数");
        }
        String fullArgs = extractFullArgs(cmdline);
        if (fullArgs.isEmpty()) {
            throw new ApiException(502, "cmdline 中未解析到有效参数");
        }
        String oldArgs = currentArgs();
        List<Map<String, Object>> diff = diffArgs(oldArgs, fullArgs);
        upsertRow(fullArgs, "snapshot");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("args", fullArgs);
        m.put("source", "snapshot");
        m.put("diff", diff);
        return m;
    }

    /** 供启动脚本生成/状态探测使用：当前完整参数行（无记录时回退基线）。 */
    public String currentArgs() {
        ModelConfig row = mapper.findRow(1L);
        return row == null ? defaultArgs : argsFromJson(row.getConfigJson());
    }

    /** 参数行 → 启动脚本多行格式（与模板 {{ARGS}} 位置对齐）。 */
    public String formatForScript(String args) {
        return String.join(" \\\n        ", tokenize(args));
    }

    /** 校验完整参数行：只允许安全 flag/value 词法（防注入）。 */
    public static void validateArgs(String args) {
        if (args == null || args.trim().isEmpty()) {
            throw new ApiException(400, "参数行不能为空");
        }
        if (args.length() > MAX_ARGS_LEN) {
            throw new ApiException(400, "参数行过长（上限 " + MAX_ARGS_LEN + " 字符）");
        }
        List<String> tokens = tokenize(args);
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (!t.startsWith("-") || !FLAG_RE.matcher(t).matches()) {
                throw new ApiException(400, "无法识别的参数: " + t);
            }
            if (i + 1 < tokens.size() && !tokens.get(i + 1).startsWith("-")) {
                String v = tokens.get(i + 1);
                if (!VALUE_RE.matcher(v).matches()) {
                    throw new ApiException(400, "参数 " + t + " 的值不合法: " + v);
                }
                i++;
            }
        }
    }

    /** 从 llama-server 进程 cmdline 提取完整参数行（去掉二进制路径）。 */
    public static String extractFullArgs(String cmdline) {
        List<String> tokens = tokenize(cmdline);
        return tokens.size() > 1 ? String.join(" ", tokens.subList(1, tokens.size())) : "";
    }

    /** 从参数行解析 --port 的值，缺失时回退配置端口。 */
    public static int portFromArgs(String args, int fallback) {
        String v = flagValueFromArgs(args, "--port");
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 从参数行取第一个匹配 flag 的 value；不存在返回 null。 */
    public static String flagValueFromArgs(String args, String flag) {
        if (args == null || args.isBlank()) {
            return null;
        }
        List<String> tokens = tokenize(args);
        for (int i = 0; i < tokens.size() - 1; i++) {
            if (flag.equals(tokens.get(i)) && !tokens.get(i + 1).startsWith("-")) {
                return tokens.get(i + 1);
            }
        }
        return null;
    }

    /** config_json → args 参数行。 */
    public static String argsFromJson(String json) {
        try {
            Map<String, Object> m = new ObjectMapper().readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
            Object a = m.get("args");
            return a == null ? "" : String.valueOf(a);
        } catch (Exception e) {
            return "";
        }
    }

    public static List<String> tokenize(String s) {
        List<String> tokens = new ArrayList<>();
        for (String t : s.trim().split("\\s+")) {
            if (!t.isEmpty()) {
                tokens.add(t);
            }
        }
        return tokens;
    }

    /** 片段 → 有序 flag→value 映射（裸 flag 的 value 为空串），用于 diff。 */
    static Map<String, String> parseFragment(String args) {
        Map<String, String> m = new LinkedHashMap<>();
        List<String> tokens = tokenize(args);
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (!t.startsWith("-")) {
                continue;
            }
            String v = (i + 1 < tokens.size() && !tokens.get(i + 1).startsWith("-")) ? tokens.get(i + 1) : "";
            m.put(t, v);
            if (!v.isEmpty()) {
                i++;
            }
        }
        return m;
    }

    /** 参数级 diff：flag 级新增/修改/删除（old 或 new 为 null 表示另一侧不存在）。 */
    public List<Map<String, Object>> diffArgs(String oldArgs, String newArgs) {
        Map<String, String> o = parseFragment(oldArgs);
        Map<String, String> n = parseFragment(newArgs);
        List<Map<String, Object>> diff = new ArrayList<>();
        for (Map.Entry<String, String> e : n.entrySet()) {
            String ov = o.get(e.getKey());
            if (!e.getValue().equals(ov)) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("flag", e.getKey());
                d.put("old", ov == null ? null : ov);
                d.put("new", e.getValue());
                diff.add(d);
            }
        }
        for (String k : o.keySet()) {
            if (!n.containsKey(k)) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("flag", k);
                d.put("old", o.get(k));
                d.put("new", null);
                diff.add(d);
            }
        }
        return diff;
    }

    static boolean sameTokens(String a, String b) {
        return tokenize(a).equals(tokenize(b));
    }

    private ModelConfig ensureRow() {
        ModelConfig row = mapper.findRow(1L);
        if (row == null) {
            row = new ModelConfig();
            row.setId(1L);
            row.setConfigJson(toJson(Map.of("args", defaultArgs)));
            row.setSource("default");
            row.setUpdatedAt(LocalDateTime.now());
            mapper.insertRow(row);
            log.info("model_config initialized with default full args line");
            return row;
        }
        if (!row.getConfigJson().contains("\"args\"")) {
            // 旧版结构（params JSON）惰性迁移为完整参数行
            Map<String, Object> legacy = fromJson(row.getConfigJson());
            String mid = legacyParamsToArgs(legacy);
            if (mid.isEmpty()) {
                throw new ApiException(500, "model_config 存在无法迁移的旧格式");
            }
            String args = "-m " + props.getModelPath() + " -mm " + props.getMmprojPath() + " " + mid
                    + " --host 0.0.0.0 --port " + props.getApiPort() + " --no-log-timestamps";
            row.setConfigJson(toJson(Map.of("args", args)));
            row.setUpdatedAt(LocalDateTime.now());
            mapper.updateRow(row);
            log.info("model_config migrated from legacy params to full args line");
        }
        return row;
    }

    private void upsertRow(String args, String source) {
        ModelConfig row = mapper.findRow(1L);
        if (row == null) {
            row = new ModelConfig();
            row.setId(1L);
            row.setConfigJson(toJson(Map.of("args", args)));
            row.setSource(source);
            row.setUpdatedAt(LocalDateTime.now());
            mapper.insertRow(row);
        } else {
            row.setConfigJson(toJson(Map.of("args", args)));
            row.setSource(source);
            row.setUpdatedAt(LocalDateTime.now());
            mapper.updateRow(row);
        }
    }

    private String normalize(String args) {
        return args.trim().replaceAll("\\s+", " ");
    }

    /** 旧版 params JSON → args 片段（与旧 buildArgs 的 flag 映射一致）。 */
    private String legacyParamsToArgs(Map<String, Object> p) {
        List<String> lines = new ArrayList<>();
        addValue(lines, "-a", p.get("alias"));
        addValue(lines, "-ngl", p.get("ngl"));
        addValue(lines, "-ts", p.get("tensorSplit"));
        addValue(lines, "-sm", p.get("sm"));
        addValue(lines, "-c", p.get("ctx"));
        addValue(lines, "-ctk", p.get("ctk"));
        addValue(lines, "-ctv", p.get("ctv"));
        addValue(lines, "-np", p.get("np"));
        addFlag(lines, "--no-kv-unified", p.get("noKvUnified"));
        addValue(lines, "--spec-type", p.get("specType"));
        addValue(lines, "--spec-draft-n-max", p.get("specDraftNMax"));
        addValue(lines, "--spec-draft-p-min", p.get("specDraftPMin"));
        addValue(lines, "--spec-draft-p-split", p.get("specDraftPSplit"));
        addValue(lines, "-fa", p.get("flashAttention"));
        addValue(lines, "-t", p.get("threads"));
        addValue(lines, "-tb", p.get("threadsBatch"));
        addValue(lines, "--batch-size", p.get("batchSize"));
        addValue(lines, "--ubatch-size", p.get("ubatchSize"));
        addValue(lines, "-cram", p.get("cram"));
        addValue(lines, "--image-min-tokens", p.get("imageMinTokens"));
        addValue(lines, "--reasoning", p.get("reasoning"));
        addFlag(lines, "--reasoning-preserve", p.get("reasoningPreserve"));
        addFlag(lines, "--jinja", p.get("jinja"));
        return String.join(" ", lines);
    }

    private void addValue(List<String> lines, String flag, Object value) {
        if (value != null && !String.valueOf(value).isEmpty()) {
            lines.add(flag + " " + value);
        }
    }

    private void addFlag(List<String> lines, String flag, Object value) {
        if (Boolean.TRUE.equals(value)) {
            lines.add(flag);
        }
    }

    private String toJson(Map<String, Object> params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            throw new ApiException(500, "参数序列化失败");
        }
    }

    private Map<String, Object> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            throw new ApiException(500, "参数解析失败");
        }
    }
}
