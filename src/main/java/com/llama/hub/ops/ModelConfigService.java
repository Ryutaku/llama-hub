package com.llama.hub.ops;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.llama.hub.config.OpsProperties;
import com.llama.hub.mapper.ModelConfigMapper;
import com.llama.hub.model.ConfigSaveResult;
import com.llama.hub.model.DiffItem;
import com.llama.hub.model.ModelConfig;
import com.llama.hub.model.ModelConfigInfo;
import com.llama.hub.model.ModelSnapshotResult;
import com.llama.hub.service.ApiException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 模型启动参数：唯一来源是 H2 model_config 表（单行，存完整参数行 + 环境变量行）。
 * 完整参数行 = llama-server 进程的全部参数（不含二进制路径），-m/-mm/--host/--port 等均可自由增删，
 * UI 直接编辑，启动脚本原样渲染进 start-gateway.sh，重启生效；
 * 环境变量行 = 白名单前缀的 K=V 集合，快照从运行进程 environ 提取，脚本渲染为 export 行；
 * 快照同时抓取最近一次启动的日志事实行（flash attention / graph / 分卡等），只读展示。
 */
@Service
@Slf4j
public class ModelConfigService {


    private final ModelConfigMapper mapper;
    private final ModelStatusService statusService;
    private final OpsProperties props;
    private final SshService ssh;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Pattern FLAG_RE = Pattern.compile("^--[a-z][a-z0-9-]*$|^-[a-zA-Z][a-zA-Z0-9]*$");
    private static final Pattern VALUE_RE = Pattern.compile("^[A-Za-z0-9/][A-Za-z0-9._,+-/]*$");
    private static final int MAX_ARGS_LEN = 4000;

    /** 允许纳管的环境变量前缀白名单（探测抓取、校验、diff 共用）。 */
    public static final List<String> ENV_KEY_PREFIXES = List.of(
            "CUDA_VISIBLE_DEVICES", "NVIDIA_", "GGML_", "LLAMA_", "OMP_", "MKL_");
    private static final Pattern ENV_KEY_RE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Pattern ENV_VALUE_RE = Pattern.compile("^[A-Za-z0-9._,+-/]*$");
    private static final int MAX_ENV_LEN = 1000;

    /** 运行事实：最近一次启动日志中匹配这些关键词的行（只读展示）。 */
    private static final String FACT_PATTERN = "flash attention|graph|main_gpu|tensor split|offloaded|spec";
    private static final int MAX_FACTS = 50;

    /** 基线 = 2026-09-14 185 实际运行进程完整参数行（非 start.sh 旧值）。 */
    private final String defaultArgs;

    public ModelConfigService(ModelConfigMapper mapper, ModelStatusService statusService,
                              OpsProperties props, SshService ssh) {
        this.mapper = mapper;
        this.statusService = statusService;
        this.props = props;
        this.ssh = ssh;
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

    /** 当前配置：完整参数行 + 环境变量行 + 来源 + 运行进程对照（drift 表示不一致）。 */
    public ModelConfigInfo get() {
        ModelConfig row = ensureRow();
        String args = argsFromJson(row.getConfigJson());
        String env = envFromJson(row.getConfigJson());
        String cmdline = statusService.lastCmdline();
        String runningArgs = (cmdline == null || cmdline.isBlank()) ? "" : extractFullArgs(cmdline);
        String runningEnv = envLine(statusService.lastEnviron());
        ModelConfigInfo m = new ModelConfigInfo();
        m.setArgs(args);
        m.setEnv(env);
        m.setSource(row.getSource());
        m.setUpdatedAt(row.getUpdatedAt() == null ? null : row.getUpdatedAt().toString());
        m.setRunningArgs(runningArgs.isEmpty() ? null : runningArgs);
        m.setRunningEnv(runningEnv.isEmpty() ? null : runningEnv);
        m.setDrift(!runningArgs.isEmpty()
                && (!sameTokens(runningArgs, args) || !envMapsEqual(parseEnvMap(runningEnv), parseEnvMap(env))));
        return m;
    }

    /** 保存完整参数行 + 环境变量行（校验 + diff），返回归一化结果与 diff。 */
    public ConfigSaveResult save(String args, String env, String source) {
        validateArgs(args);
        validateEnv(env);
        String normalizedArgs = normalize(args);
        String normalizedEnv = normalize(env);
        ConfigSaveResult m = new ConfigSaveResult();
        m.setArgs(normalizedArgs);
        m.setEnv(normalizedEnv);
        m.setSource(source);
        m.setDiff(diffAll(currentArgs(), currentEnv(), normalizedArgs, normalizedEnv));
        upsertRow(normalizedArgs, normalizedEnv, source);
        return m;
    }

    /** 从运行进程 cmdline + environ 快照参数行与环境变量行，diff 后落库（source=snapshot）。 */
    public ModelSnapshotResult snapshot() {
        String cmdline = statusService.lastCmdline();
        if (cmdline == null || cmdline.isBlank()) {
            throw new ApiException(409, "模型未运行，无法从服务器快照参数");
        }
        String fullArgs = extractFullArgs(cmdline);
        if (fullArgs.isEmpty()) {
            throw new ApiException(502, "cmdline 中未解析到有效参数");
        }
        // 服务端只信任白名单内且词法合法的变量，其余静默丢弃（绝不抛异常）
        String fullEnv = sanitizeServerEnv(statusService.lastEnviron());
        ModelSnapshotResult m = new ModelSnapshotResult();
        m.setArgs(fullArgs);
        m.setEnv(fullEnv);
        m.setSource("snapshot");
        m.setDiff(diffAll(currentArgs(), currentEnv(), fullArgs, fullEnv));
        m.setRuntimeFacts(fetchRuntimeFacts());
        upsertRow(fullArgs, fullEnv, "snapshot");
        return m;
    }

    /** 供启动脚本生成/状态探测使用：当前完整参数行（无记录时回退基线）。 */
    public String currentArgs() {
        ModelConfig row = mapper.findRow(1L);
        return row == null ? defaultArgs : argsFromJson(row.getConfigJson());
    }

    /** 供启动脚本生成使用：当前环境变量行（无记录时为空，脚本回退 yml 的 cuda-device）。 */
    public String currentEnv() {
        ModelConfig row = mapper.findRow(1L);
        return row == null ? "" : envFromJson(row.getConfigJson());
    }

    /** 参数行 → 启动脚本多行格式（与模板 {{ARGS}} 位置对齐）。 */
    public String formatForScript(String args) {
        return String.join(" \\\n        ", tokenize(args));
    }

    /** 环境变量行 → 启动脚本 export 行（与模板 {{ENV}} 位置对齐），空行返回空串。 */
    public String formatForScriptEnv(String env) {
        Map<String, String> m = parseEnvMap(env);
        if (m.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            sb.append("export ").append(e.getKey()).append("=\"").append(e.getValue()).append("\"\n");
        }
        return sb.toString().stripTrailing();
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

    /** 校验环境变量行：K=V 结构，key 限白名单前缀，value 限安全字符（防注入）。 */
    public static void validateEnv(String env) {
        if (env == null || env.trim().isEmpty()) {
            return;
        }
        if (env.length() > MAX_ENV_LEN) {
            throw new ApiException(400, "环境变量行过长（上限 " + MAX_ENV_LEN + " 字符）");
        }
        for (String token : tokenize(env)) {
            int eq = token.indexOf('=');
            if (eq <= 0) {
                throw new ApiException(400, "环境变量必须为 K=V 形式: " + token);
            }
            String key = token.substring(0, eq);
            String value = token.substring(eq + 1);
            if (!ENV_KEY_RE.matcher(key).matches()) {
                throw new ApiException(400, "环境变量名不合法: " + key);
            }
            boolean allowed = false;
            for (String prefix : ENV_KEY_PREFIXES) {
                if (key.startsWith(prefix)) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                throw new ApiException(400, "环境变量 " + key + " 不在纳管白名单内（"
                        + String.join("/", ENV_KEY_PREFIXES) + "）");
            }
            if (!ENV_VALUE_RE.matcher(value).matches()) {
                throw new ApiException(400, "环境变量 " + key + " 的值不合法: " + value);
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

    /** config_json → env 环境变量行（旧记录无 env 键时为空）。 */
    public static String envFromJson(String json) {
        try {
            Map<String, Object> m = new ObjectMapper().readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
            Object a = m.get("env");
            return a == null ? "" : String.valueOf(a);
        } catch (Exception e) {
            return "";
        }
    }

    /** 环境变量行 → 有序 K→V 映射，用于 diff 与比较。 */
    static Map<String, String> parseEnvMap(String env) {
        Map<String, String> m = new LinkedHashMap<>();
        if (env == null) {
            return m;
        }
        for (String token : tokenize(env)) {
            int eq = token.indexOf('=');
            if (eq > 0) {
                m.put(token.substring(0, eq), token.substring(eq + 1));
            }
        }
        return m;
    }

    /** Map → "K=V K=V" 行（保持插入序）。 */
    static String envLine(Map<String, String> m) {
        if (m == null || m.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> e : m.entrySet()) {
            parts.add(e.getKey() + "=" + e.getValue());
        }
        return String.join(" ", parts);
    }

    static boolean envMapsEqual(Map<String, String> a, Map<String, String> b) {
        return a.equals(b);
    }

    /** 服务端 environ 中的变量逐个过白名单与词法校验，不合法的静默丢弃。 */
    static String sanitizeServerEnv(Map<String, String> env) {
        if (env == null) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (Map.Entry<String, String> e : env.entrySet()) {
            String token = e.getKey() + "=" + e.getValue();
            try {
                validateEnv(token);
                kept.add(token);
            } catch (ApiException ignored) {
            }
        }
        return String.join(" ", kept);
    }

    /** 参数行 + 环境变量行的合并 diff（kind 区分 arg / env）。 */
    private List<DiffItem> diffAll(String oldArgs, String oldEnv, String newArgs, String newEnv) {
        List<DiffItem> diff = new ArrayList<>();
        for (DiffItem d : diffArgs(oldArgs, newArgs)) {
            d.setKind("arg");
            diff.add(d);
        }
        Map<String, String> o = parseEnvMap(oldEnv);
        Map<String, String> n = parseEnvMap(newEnv);
        for (Map.Entry<String, String> e : n.entrySet()) {
            if (!e.getValue().equals(o.get(e.getKey()))) {
                diff.add(envDiffItem(e.getKey(), o.get(e.getKey()), e.getValue()));
            }
        }
        for (String k : o.keySet()) {
            if (!n.containsKey(k)) {
                diff.add(envDiffItem(k, o.get(k), null));
            }
        }
        return diff;
    }

    private DiffItem envDiffItem(String key, String oldValue, String newValue) {
        DiffItem d = new DiffItem();
        d.setKind("env");
        d.setFlag(key);
        d.setOld(oldValue);
        d.setNewVal(newValue);
        return d;
    }

    /** 最近一次启动的日志事实行（llama.log 中最后一个 "llama-server starting:" 之后匹配关键词的行）。 */
    public List<String> fetchRuntimeFacts() {
        try {
            SshService.ExecResult res = ssh.exec(factsCommand(), 10);
            if (!res.ok()) {
                return List.of();
            }
            List<String> facts = new ArrayList<>();
            for (String line : res.output().split("\n")) {
                line = line.trim();
                if (line.startsWith("FACT:")) {
                    line = line.substring("FACT:".length()).trim();
                    if (!line.isEmpty()) {
                        facts.add(line);
                    }
                    if (facts.size() >= MAX_FACTS) {
                        break;
                    }
                }
            }
            return facts;
        } catch (Exception e) {
            log.warn("fetch runtime facts failed: {}", e.toString());
            return List.of();
        }
    }

    private String factsCommand() {
        String logFile = props.logFile();
        return "last=$(grep -n 'llama-server starting:' '" + logFile + "' 2>/dev/null | tail -1 | cut -d: -f1); "
                + "[ -n \"$last\" ] || last=1; "
                + "tail -n +\"$last\" '" + logFile + "' 2>/dev/null"
                + " | grep -m " + MAX_FACTS + " -E '" + FACT_PATTERN + "'"
                + " | cut -c1-400"
                + " | while IFS= read -r l; do printf 'FACT:%s\\n' \"$l\"; done";
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

    /** 给定参数行/环境变量行与当前配置的合并 diff（版本预览用，不落库）。 */
    public List<DiffItem> diffWith(String args, String env) {
        return diffAll(currentArgs(), currentEnv(), args, env);
    }

    /** 参数级 diff：flag 级新增/修改/删除（old 或 new 为 null 表示另一侧不存在）。 */
    public List<DiffItem> diffArgs(String oldArgs, String newArgs) {
        Map<String, String> o = parseFragment(oldArgs);
        Map<String, String> n = parseFragment(newArgs);
        List<DiffItem> diff = new ArrayList<>();
        for (Map.Entry<String, String> e : n.entrySet()) {
            String ov = o.get(e.getKey());
            if (!e.getValue().equals(ov)) {
                DiffItem d = new DiffItem();
                d.setFlag(e.getKey());
                d.setOld(ov);
                d.setNewVal(e.getValue());
                diff.add(d);
            }
        }
        for (String k : o.keySet()) {
            if (!n.containsKey(k)) {
                DiffItem d = new DiffItem();
                d.setFlag(k);
                d.setOld(o.get(k));
                d.setNewVal(null);
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

    private void upsertRow(String args, String env, String source) {
        ModelConfig row = mapper.findRow(1L);
        if (row == null) {
            row = new ModelConfig();
            row.setId(1L);
            row.setConfigJson(toJson(Map.of("args", args, "env", env)));
            row.setSource(source);
            row.setUpdatedAt(LocalDateTime.now());
            mapper.insertRow(row);
        } else {
            row.setConfigJson(toJson(Map.of("args", args, "env", env)));
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
